package com.example.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.automation.PendingActionConfirmation
import com.example.automation.PhoneAutomationManager
import com.example.core.AppCheckStateHolder
import com.example.core.AppCheckStatus
import com.example.core.AurixIdentity
import com.example.core.AurixLanguage
import com.example.core.AurixVoice
import com.example.core.SystemPromptBuilder
import com.example.core.ensureAppCheckTokenFresh
import com.example.core.prefetchAppCheckHealth
import com.example.data.local.AurixDatabase
import com.example.data.local.AurixRepository
import com.example.data.local.AurixSettings
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryFactEntity
import com.example.data.local.MessageEntity
import com.example.data.local.SettingsRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "AurixAssistantEngine"

enum class VoiceSessionState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    SPEAKING,
    MUTED,
    ERROR
}

sealed interface TextChatState {
    data object Idle : TextChatState
    data object Sending : TextChatState
    data class Streaming(val text: String) : TextChatState
    data class Error(val message: String) : TextChatState
}

/**
 * Process-wide singleton coordinator shared by MainActivity/ViewModel and AurixAssistantService
 * floating overlay so both surfaces stay synchronized on the same LiveSession, Room DB, and settings.
 */
@OptIn(ExperimentalCoroutinesApi::class, PublicPreviewAPI::class)
class AurixAssistantEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository = SettingsRepository(appContext)
    val aurixRepository = AurixRepository(AurixDatabase.getInstance(appContext).aurixDao())
    val automationManager = PhoneAutomationManager(appContext)

    val settings: StateFlow<AurixSettings> = settingsRepository.settingsFlow
        .stateIn(scope, SharingStarted.Eagerly, AurixSettings())

    val conversations: StateFlow<List<ConversationEntity>> = aurixRepository.conversations
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val memoryFacts: StateFlow<List<MemoryFactEntity>> = aurixRepository.memoryFacts
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val totalMessageCount: StateFlow<Int> = aurixRepository.totalMessageCount
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _activeConversationId = MutableStateFlow(-1L)
    val activeConversationId: StateFlow<Long> = _activeConversationId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _activeConversationId
        .flatMapLatest { id ->
            if (id <= 0L) flowOf(emptyList()) else aurixRepository.observeMessages(id)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _voiceState = MutableStateFlow(VoiceSessionState.DISCONNECTED)
    val voiceState: StateFlow<VoiceSessionState> = _voiceState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _liveUserTranscript = MutableStateFlow("")
    val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

    private val _liveAiTranscript = MutableStateFlow("")
    val liveAiTranscript: StateFlow<String> = _liveAiTranscript.asStateFlow()

    private val _textChatState = MutableStateFlow<TextChatState>(TextChatState.Idle)
    val textChatState: StateFlow<TextChatState> = _textChatState.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val appCheckStatus: StateFlow<AppCheckStatus> = AppCheckStateHolder.statusFlow
    val appCheckDiagnostic: StateFlow<String> = AppCheckStateHolder.diagnosticFlow

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    val pendingConfirmation: StateFlow<PendingActionConfirmation?> = automationManager.pendingConfirmation

    private var liveSession: LiveSession? = null
    private val inputTranscriptBuffer = StringBuilder()
    private val outputTranscriptBuffer = StringBuilder()
    private var userTranscriptCommittedForTurn = false

    private val liveAudioEngine = LiveAudioEngine(
        context = appContext,
        onInputTranscriptChunk = { chunk ->
            synchronized(inputTranscriptBuffer) {
                inputTranscriptBuffer.append(chunk)
                _liveUserTranscript.value = inputTranscriptBuffer.toString().trim()
            }
        },
        onOutputTranscriptChunk = { chunk ->
            // Commit accumulated user speech first before AI output begins
            commitPendingUserTranscriptIfNeeded()
            synchronized(outputTranscriptBuffer) {
                outputTranscriptBuffer.append(chunk)
                _liveAiTranscript.value = outputTranscriptBuffer.toString().trim()
            }
        },
        onTurnComplete = {
            scope.launch {
                commitPendingUserTranscriptIfNeeded()
                commitPendingAiTranscriptIfNeeded()
                _voiceState.value = if (_isMuted.value) VoiceSessionState.MUTED else VoiceSessionState.LISTENING
            }
        },
        onInterrupted = {
            scope.launch {
                commitPendingAiTranscriptIfNeeded(suffix = " [interrupted]")
                _voiceState.value = if (_isMuted.value) VoiceSessionState.MUTED else VoiceSessionState.LISTENING
            }
        },
        onToolCall = { call ->
            val currentLang = settings.value.language
            val convId = ensureActiveConversation()
            AurixTools.executeToolCall(
                call = call,
                automationManager = automationManager,
                settingsRepository = settingsRepository,
                aurixRepository = aurixRepository,
                onSystemActionLogged = { actionMsg ->
                    aurixRepository.appendMessage(
                        conversationId = convId,
                        role = "system_action",
                        content = actionMsg,
                        inputMode = "ACTION",
                        languageCode = currentLang.code
                    )
                },
                onLanguageSwitched = { newLang ->
                    _statusBanner.value = "Language set to ${newLang.nativeName}"
                }
            )
        },
        onSessionError = { err ->
            scope.launch {
                commitPendingUserTranscriptIfNeeded()
                commitPendingAiTranscriptIfNeeded()
                val errLower = err.lowercase()
                if (errLower.contains("403") || errLower.contains("app check") || errLower.contains("appcheck")) {
                    ensureAppCheckTokenFresh(appContext, forceRefresh = true, clearCacheAndReinstall = true)
                }
                _voiceState.value = VoiceSessionState.ERROR
                _statusBanner.value = formatLiveErrorBanner(err, settings.value.language)
            }
        },
        onSessionClosed = { hadServerResponse ->
            scope.launch {
                commitPendingUserTranscriptIfNeeded()
                commitPendingAiTranscriptIfNeeded()
                if (!hadServerResponse && _voiceState.value != VoiceSessionState.ERROR) {
                    ensureAppCheckTokenFresh(appContext, forceRefresh = true, clearCacheAndReinstall = false)
                    _voiceState.value = VoiceSessionState.ERROR
                    _statusBanner.value = if (!hasActiveInternetConnection()) {
                        if (settings.value.language == AurixLanguage.ENGLISH) {
                            "No internet connection. Please enable Wi-Fi or mobile data and tap to retry."
                        } else {
                            "ফোনে ইন্টারনেট সংযোগ নেই। Wi-Fi বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"
                        }
                    } else if (settings.value.language == AurixLanguage.ENGLISH) {
                        "Gemini Live closed before replying. Token refreshed—please tap to retry."
                    } else {
                        "Gemini Live সংযোগ উত্তর আসার আগেই বন্ধ হয়েছে। আবার চেষ্টা করতে ট্যাপ করুন।"
                    }
                } else if (_voiceState.value != VoiceSessionState.ERROR) {
                    _voiceState.value = VoiceSessionState.DISCONNECTED
                }
            }
        }
    )

    val micAmplitude: StateFlow<Float> = liveAudioEngine.micAmplitude
    val aiAmplitude: StateFlow<Float> = liveAudioEngine.aiAmplitude

    private val conversationMutex = Mutex()
    private val textTurnMutex = Mutex()

    @Volatile
    private var lastFailedPrompt: String? = null

    init {
        scope.launch {
            settingsRepository.ensurePersistentIdentity()
            val initial = settingsRepository.getCurrentSettings()
            conversationMutex.withLock {
                val convId = aurixRepository.ensureConversation(
                    preferredId = initial.activeConversationId,
                    languageCode = initial.language.code
                )
                _activeConversationId.value = convId
                settingsRepository.setActiveConversationId(convId)
            }
            liveAudioEngine.updatePlaybackSettings(initial.voiceSpeed, initial.volume)
        }
        scope.launch {
            liveAudioEngine.isAiSpeaking.collect { speaking ->
                if (_voiceState.value == VoiceSessionState.LISTENING ||
                    _voiceState.value == VoiceSessionState.SPEAKING ||
                    _voiceState.value == VoiceSessionState.MUTED
                ) {
                    _voiceState.value = when {
                        speaking -> VoiceSessionState.SPEAKING
                        _isMuted.value -> VoiceSessionState.MUTED
                        else -> VoiceSessionState.LISTENING
                    }
                }
            }
        }
        scope.launch {
            settings.collect { s ->
                liveAudioEngine.updatePlaybackSettings(s.voiceSpeed, s.volume)
            }
        }
    }

    fun refreshAppCheckStatus() {
        prefetchAppCheckHealth(appContext)
    }

    fun verifyAndRefreshAppCheck() {
        scope.launch {
            val lang = settings.value.language
            val ok = ensureAppCheckTokenFresh(
                context = appContext,
                forceRefresh = true,
                clearCacheAndReinstall = true
            )
            _statusBanner.value = if (ok) {
                if (lang == AurixLanguage.ENGLISH) {
                    "App Check token verified and refreshed."
                } else {
                    "App Check নিরাপত্তা টোকেন সফলভাবে রিফ্রেশ হয়েছে।"
                }
            } else {
                if (lang == AurixLanguage.ENGLISH) {
                    "App Check refresh failed. Verify internet and Automatic Date & Time on your phone."
                } else {
                    "App Check রিফ্রেশ করা যায়নি। ইন্টারনেট এবং ফোনের Automatic Date & Time চালু আছে কিনা দেখুন।"
                }
            }
        }
    }

    fun onAppReturnedToForeground() {
        _isAppInForeground.value = true
        scope.launch {
            if (AppCheckStateHolder.status == AppCheckStatus.ERROR) {
                ensureAppCheckTokenFresh(appContext, forceRefresh = true, clearCacheAndReinstall = false)
            } else {
                prefetchAppCheckHealth(appContext)
            }
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    private suspend fun preflightAppCheckIfNeeded() {
        if (AppCheckStateHolder.status == AppCheckStatus.ERROR ||
            AppCheckStateHolder.status == AppCheckStatus.INITIALIZING
        ) {
            ensureAppCheckTokenFresh(appContext, forceRefresh = true, clearCacheAndReinstall = false)
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun notifyMicPermissionDenied() {
        val lang = settings.value.language
        _statusBanner.value = if (lang == AurixLanguage.ENGLISH) {
            "Microphone permission is required for live voice. You can still type messages below, or enable Mic in Android Settings."
        } else {
            "লাইভ ভয়েসের জন্য মাইক্রোফোন অনুমতি প্রয়োজন। আপনি নিচে লিখেও চ্যাট করতে পারেন অথবা Settings থেকে Mic অনুমতি দিন।"
        }
    }

    private suspend fun ensureActiveConversation(): Long = conversationMutex.withLock {
        val current = _activeConversationId.value
        val preferred = if (current > 0L) current else settingsRepository.getCurrentSettings().activeConversationId
        val lang = settings.value.language.code
        val ensured = aurixRepository.ensureConversation(preferred, lang)
        if (ensured != current) {
            _activeConversationId.value = ensured
            settingsRepository.setActiveConversationId(ensured)
        }
        ensured
    }

    fun selectConversation(conversationId: Long) {
        scope.launch {
            _activeConversationId.value = conversationId
            settingsRepository.setActiveConversationId(conversationId)
        }
    }

    fun startNewConversation() {
        scope.launch {
            val newId = aurixRepository.createNewConversation(settings.value.language.code)
            _activeConversationId.value = newId
            settingsRepository.setActiveConversationId(newId)
        }
    }

    fun deleteConversation(conversationId: Long) {
        scope.launch {
            aurixRepository.deleteConversation(conversationId)
            if (_activeConversationId.value == conversationId) {
                val next = aurixRepository.createNewConversation(settings.value.language.code)
                _activeConversationId.value = next
                settingsRepository.setActiveConversationId(next)
            }
        }
    }

    fun clearAllHistory() {
        scope.launch {
            aurixRepository.clearAllConversations()
            val next = aurixRepository.createNewConversation(settings.value.language.code)
            _activeConversationId.value = next
            settingsRepository.setActiveConversationId(next)
        }
    }

    fun addMemoryFact(fact: String) {
        scope.launch {
            val saved = aurixRepository.saveMemoryFact("preference", fact)
            if (!saved) {
                _statusBanner.value = "Sensitive credentials (passwords/keys/OTPs) cannot be saved in memory."
            }
        }
    }

    fun deleteMemoryFact(id: Long) {
        scope.launch { aurixRepository.deleteMemoryFact(id) }
    }

    fun clearAllMemories() {
        scope.launch { aurixRepository.clearAllMemoryFacts() }
    }

    fun startVoiceConversation() {
        if (_voiceState.value == VoiceSessionState.CONNECTING ||
            _voiceState.value == VoiceSessionState.LISTENING ||
            _voiceState.value == VoiceSessionState.SPEAKING ||
            _voiceState.value == VoiceSessionState.MUTED
        ) {
            return
        }

        scope.launch {
            _voiceState.value = VoiceSessionState.CONNECTING
            _statusBanner.value = null
            _isMuted.value = false
            liveAudioEngine.setMuted(false)

            val lang = settings.value.language
            if (!hasActiveInternetConnection()) {
                _voiceState.value = VoiceSessionState.ERROR
                _statusBanner.value = if (lang == AurixLanguage.ENGLISH) {
                    "No internet connection. Turn on Wi-Fi or mobile data and tap to retry."
                } else {
                    "ফোনে ইন্টারনেট সংযোগ নেই। Wi-Fi বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"
                }
                return@launch
            }

            try {
                preflightAppCheckIfNeeded()

                val convId = ensureActiveConversation()
                val currentSettings = settingsRepository.getCurrentSettings()
                val memories = if (currentSettings.memoryEnabled) {
                    aurixRepository.getMemoryFactsOnce().map { it.fact }
                } else {
                    emptyList()
                }

                val systemPrompt = SystemPromptBuilder.build(
                    language = currentSettings.language,
                    memories = memories
                )

                val liveGenConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    speechConfig = SpeechConfig(voice = Voice(currentSettings.voice.apiName))
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                    contextWindowCompression = ContextWindowCompressionConfig(
                        triggerTokens = 16000,
                        slidingWindow = SlidingWindow(targetTokens = 4000)
                    )
                    realtimeInputConfig = RealtimeInputConfig.builder()
                        .setAutomaticActivityDetection(
                            ActivityDetectionConfig.builder()
                                .setStartSensitivity(ActivityDetectionConfig.Sensitivity.LOW)
                                .setEndSensitivity(ActivityDetectionConfig.Sensitivity.LOW)
                                .setPrefixPaddingMs(120)
                                .setSilenceDurationMs(700)
                                .build()
                        )
                        .setActivityHandling(RealtimeInputConfig.ActivityHandling.INTERRUPT)
                        .setTurnCoverage(RealtimeInputConfig.TurnCoverage.ONLY_ACTIVITY)
                        .build()
                }

                val sysContent = content { text(systemPrompt) }
                val aiClient = Firebase.ai(backend = GenerativeBackend.googleAI())
                val session = connectLiveModelWithRecovery(
                    aiClient = aiClient,
                    liveGenConfig = liveGenConfig,
                    sysContent = sysContent
                )
                liveSession = session

                // Replay brief recent alternating context without risking session startup failure
                try {
                    val recentTurns = ChatHistorySanitizer.normalizeTurns(
                        messages = aurixRepository.getMessagesOnce(convId),
                        maxMessages = 8
                    )
                    for (turn in recentTurns) {
                        session.send(
                            content = content(turn.role) { text(turn.text) },
                            turnComplete = false
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping live history replay: ${e.message}")
                }

                liveAudioEngine.updatePlaybackSettings(currentSettings.voiceSpeed, currentSettings.volume)
                liveAudioEngine.start(session)
                _voiceState.value = VoiceSessionState.LISTENING
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out connecting to Gemini Live API", e)
                _voiceState.value = VoiceSessionState.ERROR
                _statusBanner.value = if (lang == AurixLanguage.ENGLISH) {
                    "Gemini Live connection timed out after 30s. Check internet and tap to retry."
                } else {
                    "Gemini Live সংযোগে বেশি সময় লাগছে (Timeout)। ইন্টারনেট চেক করে আবার চেষ্টা করুন।"
                }
                liveAudioEngine.stopSyncResources()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Live voice session", e)
                _voiceState.value = VoiceSessionState.ERROR
                _statusBanner.value = formatUserFriendlyAiError(e, lang)
                liveAudioEngine.stopSyncResources()
            }
        }
    }

    private suspend fun connectLiveModelWithRecovery(
        aiClient: com.google.firebase.ai.FirebaseAI,
        liveGenConfig: com.google.firebase.ai.type.LiveGenerationConfig,
        sysContent: Content
    ): LiveSession {
        suspend fun connectSingle(modelName: String): LiveSession {
            val model: LiveGenerativeModel = aiClient.liveModel(
                modelName = modelName,
                generationConfig = liveGenConfig,
                tools = listOf(AurixTools.tool),
                systemInstruction = sysContent
            )
            return withTimeout(30_000L) { model.connect() }
        }

        val modelsToTry = listOf(AurixIdentity.LIVE_MODEL_NAME, AurixIdentity.LIVE_FALLBACK_MODEL_NAME)
        var recoveredAppCheck = false
        var lastError: Exception? = null

        for (modelName in modelsToTry) {
            try {
                return connectSingle(modelName)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                lastError = e
                Log.w(TAG, "Live model $modelName timed out during connect.")
            } catch (e: Exception) {
                lastError = e
                if (isAppCheckError(e) && !recoveredAppCheck) {
                    recoveredAppCheck = true
                    Log.w(TAG, "Live connect hit App Check error on $modelName; clearing cache & refreshing token before retry.")
                    val refreshed = ensureAppCheckTokenFresh(
                        context = appContext,
                        forceRefresh = true,
                        clearCacheAndReinstall = true
                    )
                    if (refreshed) {
                        return connectSingle(modelName)
                    }
                }
                Log.w(TAG, "Live model $modelName failed (${e.message}); checking next fallback if available.")
            }
        }

        throw (lastError ?: IllegalStateException("Unable to connect to Gemini Live API."))
    }

    fun stopVoiceConversation() {
        scope.launch {
            commitPendingUserTranscriptIfNeeded()
            commitPendingAiTranscriptIfNeeded()
            liveAudioEngine.stop()
            try {
                liveSession?.stopReceiving()
                liveSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing LiveSession: ${e.message}")
            }
            liveSession = null
            _isMuted.value = false
            _voiceState.value = VoiceSessionState.DISCONNECTED
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        liveAudioEngine.setMuted(next)
        if (_voiceState.value == VoiceSessionState.LISTENING || _voiceState.value == VoiceSessionState.MUTED) {
            _voiceState.value = if (next) VoiceSessionState.MUTED else VoiceSessionState.LISTENING
        }
    }

    fun clearTextChatError() {
        if (_textChatState.value is TextChatState.Error) {
            _textChatState.value = TextChatState.Idle
        }
    }

    fun retryLastMessage() {
        val promptToRetry = lastFailedPrompt
            ?: messages.value.lastOrNull { it.role == "user" }?.content
            ?: return
        sendTextMessage(promptToRetry, appendUserMessage = false)
    }

    fun sendTextMessage(rawText: String, appendUserMessage: Boolean = true) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return

        scope.launch {
            textTurnMutex.withLock {
                val convId = ensureActiveConversation()
                val currentSettings = settingsRepository.getCurrentSettings()
                _statusBanner.value = null

                // Quick local language command detection so preference updates immediately
                detectExplicitLanguageSwitch(trimmed)?.let { switched ->
                    settingsRepository.setLanguage(switched)
                }

                val updatedLang = settingsRepository.getCurrentSettings().language
                if (appendUserMessage) {
                    aurixRepository.appendMessage(
                        conversationId = convId,
                        role = "user",
                        content = trimmed,
                        inputMode = "TEXT",
                        languageCode = updatedLang.code
                    )
                }

                // If live voice session is active, send into the same LiveSession so voice + text stay unified
                val activeLive = liveSession
                if (activeLive != null && !activeLive.isClosed() &&
                    (_voiceState.value == VoiceSessionState.LISTENING ||
                        _voiceState.value == VoiceSessionState.SPEAKING ||
                        _voiceState.value == VoiceSessionState.MUTED)
                ) {
                    try {
                        activeLive.send(trimmed, turnComplete = true)
                        _textChatState.value = TextChatState.Idle
                        lastFailedPrompt = null
                        return@withLock
                    } catch (e: Exception) {
                        Log.w(TAG, "Live session text send failed; falling back to text model: ${e.message}")
                    }
                }

                // Otherwise generate via Gemini 3.8 Flash with tool calling & sanitized Room history
                lastFailedPrompt = trimmed
                _textChatState.value = TextChatState.Sending
                try {
                    val memories = if (currentSettings.memoryEnabled) {
                        aurixRepository.getMemoryFactsOnce().map { it.fact }
                    } else {
                        emptyList()
                    }
                    val systemPrompt = SystemPromptBuilder.build(
                        language = updatedLang,
                        memories = memories
                    )

                    val allMessages = aurixRepository.getMessagesOnce(convId)
                    val historyContents = ChatHistorySanitizer.buildChatHistoryBeforeCurrentPrompt(allMessages)

                    val finalText = generateTextReplyWithFallback(
                        convId = convId,
                        prompt = trimmed,
                        systemPrompt = systemPrompt,
                        historyContents = historyContents
                    )

                    if (finalText.isNotBlank()) {
                        lastFailedPrompt = null
                        aurixRepository.appendMessage(
                            conversationId = convId,
                            role = "model",
                            content = finalText.trim(),
                            inputMode = "TEXT",
                            languageCode = settings.value.language.code
                        )
                        _textChatState.value = TextChatState.Idle
                    } else {
                        val reason = if (updatedLang == AurixLanguage.ENGLISH) {
                            "AURIX AI returned an empty response. Please tap Retry."
                        } else {
                            "AURIX AI কোনো উত্তর পাঠায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
                        }
                        Log.w(TAG, "Gemini returned empty text")
                        _textChatState.value = TextChatState.Error(reason)
                        _statusBanner.value = reason
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Text generation timed out after fallback attempts", e)
                    val offline = !hasActiveInternetConnection()
                    val timeoutMsg = if (offline) {
                        if (updatedLang == AurixLanguage.ENGLISH) {
                            "Device appears offline. Please turn on Wi-Fi or mobile data and tap Retry."
                        } else {
                            "ফোনে ইন্টারনেট সংযোগ নেই। Wi-Fi বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"
                        }
                    } else if (updatedLang == AurixLanguage.ENGLISH) {
                        "Request timed out waiting for Gemini. Please check your internet connection and tap Retry."
                    } else {
                        "সার্ভার থেকে উত্তর আসতে বেশি সময় লাগছে (Timeout)। ইন্টারনেট সংযোগ চেক করে আবার চেষ্টা করুন।"
                    }
                    _textChatState.value = TextChatState.Error(timeoutMsg)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Text generation failed", e)
                    val errMsg = formatUserFriendlyAiError(e, updatedLang)
                    _textChatState.value = TextChatState.Error(errMsg)
                }
            }
        }
    }

    private suspend fun generateTextReplyWithFallback(
        convId: Long,
        prompt: String,
        systemPrompt: String,
        historyContents: List<Content>
    ): String {
        preflightAppCheckIfNeeded()

        val now = System.currentTimeMillis()
        val primaryCooldownUntil = quotaPrefs.getLong(KEY_PRIMARY_QUOTA_COOLDOWN_UNTIL, 0L)
        val skipPrimaryForCooldown = now < primaryCooldownUntil

        val primaryAttempt = TextAttemptConfig(
            modelName = AurixIdentity.TEXT_MODEL_NAME,
            includeTools = true,
            useMinimalThinking = true,
            timeoutMs = 26_000L
        )
        val fallbackAttempt = TextAttemptConfig(
            modelName = AurixIdentity.TEXT_FALLBACK_MODEL_NAME,
            includeTools = true,
            useMinimalThinking = false,
            timeoutMs = 28_000L
        )

        val attempts = if (skipPrimaryForCooldown) {
            Log.i(
                TAG,
                "Primary model ${AurixIdentity.TEXT_MODEL_NAME} is in quota cooldown; routing directly to ${AurixIdentity.TEXT_FALLBACK_MODEL_NAME}"
            )
            listOf(fallbackAttempt)
        } else {
            listOf(primaryAttempt, fallbackAttempt)
        }

        var lastError: Exception? = null
        var retriedAppCheckRecovery = false

        for ((index, attempt) in attempts.withIndex()) {
            try {
                if (index > 0) {
                    _textChatState.value = TextChatState.Sending
                }
                return executeTextChatTurnWithSchemaRetry(
                    convId = convId,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    historyContents = historyContents,
                    attempt = attempt
                )
            } catch (e: TimeoutCancellationException) {
                lastError = e
                Log.w(
                    TAG,
                    "Text attempt ${index + 1} (${attempt.modelName}) timed out after ${attempt.timeoutMs}ms",
                    e
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (isAppCheckError(e) && !retriedAppCheckRecovery) {
                    retriedAppCheckRecovery = true
                    Log.w(
                        TAG,
                        "Text attempt (${attempt.modelName}) hit App Check 403; clearing token cache & refreshing before retry.",
                        e
                    )
                    val recovered = ensureAppCheckTokenFresh(
                        context = appContext,
                        forceRefresh = true,
                        clearCacheAndReinstall = true
                    )
                    if (recovered) {
                        try {
                            _textChatState.value = TextChatState.Sending
                            return executeTextChatTurnWithSchemaRetry(
                                convId = convId,
                                prompt = prompt,
                                systemPrompt = systemPrompt,
                                historyContents = historyContents,
                                attempt = attempt
                            )
                        } catch (retryEx: CancellationException) {
                            throw retryEx
                        } catch (retryEx: Exception) {
                            lastError = retryEx
                            if (attempt.modelName == AurixIdentity.TEXT_MODEL_NAME && isQuotaError(retryEx)) {
                                cachePrimaryQuotaCooldown(retryEx)
                            }
                            if (!isRetryableModelFailure(retryEx) || index >= attempts.lastIndex) {
                                throw retryEx
                            }
                            continue
                        }
                    }
                }

                if (attempt.modelName == AurixIdentity.TEXT_MODEL_NAME && isQuotaError(e)) {
                    cachePrimaryQuotaCooldown(e)
                }
                val hasNextAttempt = index < attempts.lastIndex
                val shouldTryFallback = hasNextAttempt && isRetryableModelFailure(e)
                if (!shouldTryFallback) {
                    throw e
                }
                Log.w(
                    TAG,
                    "Text model ${attempt.modelName} failed (${e.message}); switching to ${attempts[index + 1].modelName}",
                    e
                )
            }
        }

        throw (lastError ?: IllegalStateException("Text generation failed across all models."))
    }

    private fun cachePrimaryQuotaCooldown(e: Throwable) {
        val cooldownMs = parseRetryAfterMs(e) ?: DEFAULT_PRIMARY_QUOTA_COOLDOWN_MS
        quotaPrefs.edit()
            .putLong(KEY_PRIMARY_QUOTA_COOLDOWN_UNTIL, System.currentTimeMillis() + cooldownMs)
            .commit()
        Log.w(
            TAG,
            "Primary model ${AurixIdentity.TEXT_MODEL_NAME} hit quota limit; caching fallback to ${AurixIdentity.TEXT_FALLBACK_MODEL_NAME} for ${cooldownMs / 1000}s"
        )
    }

    private suspend fun executeTextChatTurnWithSchemaRetry(
        convId: Long,
        prompt: String,
        systemPrompt: String,
        historyContents: List<Content>,
        attempt: TextAttemptConfig
    ): String {
        return try {
            executeTextChatTurn(
                convId = convId,
                prompt = prompt,
                systemPrompt = systemPrompt,
                historyContents = historyContents,
                modelName = attempt.modelName,
                includeTools = attempt.includeTools,
                useMinimalThinking = attempt.useMinimalThinking,
                timeoutMs = attempt.timeoutMs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (ex: Exception) {
            if (!isQuotaError(ex) && attempt.useMinimalThinking && isThinkingConfigRejection(ex)) {
                Log.w(TAG, "ThinkingConfig rejected for ${attempt.modelName}, retrying without thinkingConfig", ex)
                executeTextChatTurn(
                    convId = convId,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    historyContents = historyContents,
                    modelName = attempt.modelName,
                    includeTools = attempt.includeTools,
                    useMinimalThinking = false,
                    timeoutMs = attempt.timeoutMs
                )
            } else if (!isQuotaError(ex) && attempt.includeTools && isToolSchemaRejection(ex)) {
                Log.w(TAG, "Tool declaration rejected by API for ${attempt.modelName}, retrying without tools", ex)
                executeTextChatTurn(
                    convId = convId,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    historyContents = historyContents,
                    modelName = attempt.modelName,
                    includeTools = false,
                    useMinimalThinking = attempt.useMinimalThinking,
                    timeoutMs = attempt.timeoutMs
                )
            } else {
                throw ex
            }
        }
    }

    private suspend fun executeTextChatTurn(
        convId: Long,
        prompt: String,
        systemPrompt: String,
        historyContents: List<Content>,
        modelName: String,
        includeTools: Boolean,
        useMinimalThinking: Boolean,
        timeoutMs: Long
    ): String {
        val startedAt = System.currentTimeMillis()
        Log.d(
            TAG,
            "Starting text turn model=$modelName includeTools=$includeTools minimalThinking=$useMinimalThinking history=${historyContents.size}"
        )

        val genConfig = if (useMinimalThinking) {
            generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.LOW
                }
            }
        } else {
            null
        }

        val configuredModel: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = modelName,
            generationConfig = genConfig,
            tools = if (includeTools) listOf(AurixTools.tool) else null,
            systemInstruction = content { text(systemPrompt) },
            requestOptions = RequestOptions(timeoutMs)
        )
        val chat = configuredModel.startChat(history = historyContents)

        var lastResponse: GenerateContentResponse? = null
        val textBuffer = StringBuilder()
        val pendingCalls = mutableListOf<com.google.firebase.ai.type.FunctionCallPart>()

        try {
            withTimeout(timeoutMs) {
                chat.sendMessageStream(prompt).collect { chunk ->
                    if (lastResponse == null) {
                        Log.d(TAG, "First stream chunk from $modelName in ${System.currentTimeMillis() - startedAt}ms")
                    }
                    lastResponse = chunk
                    val chunkText = extractResponseText(chunk)
                    if (chunkText.isNotEmpty()) {
                        textBuffer.append(chunkText)
                        _textChatState.value = TextChatState.Streaming(textBuffer.toString())
                    }
                    if (chunk.functionCalls.isNotEmpty()) {
                        pendingCalls.addAll(chunk.functionCalls)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // If partial text already streamed before timeout, return it instead of discarding the reply.
            val partial = textBuffer.toString().trim()
            if (partial.isNotEmpty()) {
                Log.w(TAG, "Stream timed out after receiving ${partial.length} chars; returning partial text.")
                return partial
            }
            throw timeout
        }

        // Execute any tool calls returned by Gemini in a bounded loop
        var toolRound = 0
        while (pendingCalls.isNotEmpty() && toolRound < 4) {
            toolRound++
            val callsToRun = pendingCalls.toList()
            pendingCalls.clear()

            val responseParts = mutableListOf<FunctionResponsePart>()
            for (call in callsToRun) {
                val part = AurixTools.executeToolCall(
                    call = call,
                    automationManager = automationManager,
                    settingsRepository = settingsRepository,
                    aurixRepository = aurixRepository,
                    onSystemActionLogged = { actionMsg ->
                        aurixRepository.appendMessage(
                            conversationId = convId,
                            role = "system_action",
                            content = actionMsg,
                            inputMode = "ACTION",
                            languageCode = settings.value.language.code
                        )
                    },
                    onLanguageSwitched = { newLang ->
                        _statusBanner.value = "Language set to ${newLang.nativeName}"
                    }
                )
                responseParts.add(part)
            }

            val toolContent = content("function") {
                responseParts.forEach { part(it) }
            }
            try {
                withTimeout(timeoutMs) {
                    chat.sendMessageStream(toolContent).collect { chunk ->
                        lastResponse = chunk
                        val chunkText = extractResponseText(chunk)
                        if (chunkText.isNotEmpty()) {
                            if (textBuffer.isNotEmpty() && !textBuffer.endsWith("\n")) {
                                textBuffer.append("\n")
                            }
                            textBuffer.append(chunkText)
                            _textChatState.value = TextChatState.Streaming(textBuffer.toString())
                        }
                        if (chunk.functionCalls.isNotEmpty()) {
                            pendingCalls.addAll(chunk.functionCalls)
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                val partial = textBuffer.toString().trim()
                if (partial.isNotEmpty()) {
                    Log.w(TAG, "Post-tool stream timed out after receiving ${partial.length} chars; returning partial text.")
                    return partial
                }
                throw timeout
            }
        }

        val combined = textBuffer.toString().trim()
        if (combined.isNotEmpty()) {
            Log.d(TAG, "Completed text turn with $modelName in ${System.currentTimeMillis() - startedAt}ms")
            return combined
        }
        val fallbackLast = lastResponse ?: return ""
        val fallbackText = extractResponseText(fallbackLast)
        if (fallbackText.isNotEmpty()) return fallbackText
        throw IllegalStateException(buildEmptyResponseReason(fallbackLast))
    }

    private fun extractResponseText(response: GenerateContentResponse): String {
        val candidateText = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.mapNotNull { part ->
                (part as? com.google.firebase.ai.type.TextPart)?.text?.takeIf { it.isNotEmpty() }
            }
            ?.joinToString("")
            .orEmpty()
        if (candidateText.isNotEmpty()) return candidateText
        return response.text.orEmpty()
    }

    private fun buildEmptyResponseReason(response: GenerateContentResponse): String {
        val blockReason = response.promptFeedback?.blockReason
        if (blockReason != null) {
            return "Request was blocked (${blockReason.name}). Please rephrase your message."
        }
        val finishReason = response.candidates.firstOrNull()?.finishReason
        return if (finishReason != null) {
            "AURIX AI could not produce a reply (${finishReason.name}). Please try again."
        } else {
            "AURIX AI returned an empty response. Please tap Retry."
        }
    }

    private val quotaPrefs by lazy {
        appContext.getSharedPreferences(PREFS_MODEL_QUOTA, Context.MODE_PRIVATE)
    }

    private fun isToolSchemaRejection(e: Throwable): Boolean {
        val msg = collectErrorText(e)
        return msg.contains("function_declaration") ||
            msg.contains("functiondeclaration") ||
            (msg.contains("parameters") && msg.contains("properties")) ||
            (msg.contains("tool") && (msg.contains("invalid") || msg.contains("schema")))
    }

    private fun isThinkingConfigRejection(e: Throwable): Boolean {
        val msg = collectErrorText(e)
        return msg.contains("thinking") && (msg.contains("unsupported") || msg.contains("invalid") || msg.contains("unknown"))
    }

    private fun hasActiveInternetConnection(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun formatLiveErrorBanner(rawError: String, language: AurixLanguage): String {
        val lower = rawError.lowercase()
        return when {
            !hasActiveInternetConnection() -> if (language == AurixLanguage.ENGLISH) {
                "No internet connection. Please turn on Wi-Fi or mobile data and tap to retry."
            } else {
                "ইন্টারনেট সংযোগ নেই। Wi-Fi বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"
            }
            lower.contains("403") || lower.contains("app check") || lower.contains("appcheck") -> if (language == AurixLanguage.ENGLISH) {
                "App Check refreshed. Tap to retry — or check phone Automatic Date & Time."
            } else {
                "App Check টোকেন রিফ্রেশ করা হয়েছে। আবার চেষ্টা করতে ট্যাপ করুন (সমস্যা থাকলে ফোনের Automatic Date & Time অন করুন)।"
            }
            else -> rawError
        }
    }

    private fun formatUserFriendlyAiError(e: Exception, language: AurixLanguage): String {
        val raw = e.localizedMessage ?: e.message ?: "Unknown error"
        val lower = collectErrorText(e)
        return when {
            !hasActiveInternetConnection() -> {
                if (language == AurixLanguage.ENGLISH) {
                    "No internet connection. Please turn on Wi-Fi or mobile data and tap Retry."
                } else {
                    "ফোনে ইন্টারনেট সংযোগ নেই। Wi-Fi বা মোবাইল ডেটা চালু করে আবার চেষ্টা করুন।"
                }
            }
            isQuotaError(e) -> {
                if (language == AurixLanguage.ENGLISH) {
                    "Free-tier AI quota limit has been reached for now. Please wait and retry later, or check your Gemini API plan/rate limits.\n\nDetails: $raw"
                } else {
                    "এই প্রজেক্টের ফ্রি AI ব্যবহারের সীমা (Quota) আপাতত শেষ হয়ে গেছে। কিছুক্ষণ পর আবার চেষ্টা করুন অথবা Google AI Studio-তে আপনার প্ল্যান/রেট-লিমিট চেক করুন।\n\nবিস্তারিত: $raw"
                }
            }
            isAppCheckError(e) -> {
                if (language == AurixLanguage.ENGLISH) {
                    "App Check / Authorization error (403): $raw\n\nPlease tap Retry, or verify Internet & Automatic Date/Time in Android Settings."
                } else {
                    "অ্যাপ অনুমোদন সমস্যা (App Check 403): $raw\n\nটোকেন রিফ্রেশ করা হয়েছে — আবার চেষ্টা করুন ট্যাপ করুন। তবুও সমস্যা হলে ফোনের Settings > Automatic Date & Time চালু আছে কিনা দেখুন।"
                }
            }
            lower.contains("unable to resolve host") || lower.contains("unknownhost") || lower.contains("network") -> {
                if (language == AurixLanguage.ENGLISH) {
                    "No internet connection or network error. Please check Wi-Fi/Data and retry."
                } else {
                    "ইন্টারনেট সংযোগ নেই বা নেটওয়ার্ক সমস্যা হচ্ছে। অনুগ্রহ করে সংযোগ চেক করে আবার চেষ্টা করুন।"
                }
            }
            else -> raw
        }
    }

    private data class TextAttemptConfig(
        val modelName: String,
        val includeTools: Boolean,
        val useMinimalThinking: Boolean,
        val timeoutMs: Long
    )

    fun approvePendingConfirmation() {
        scope.launch {
            val convId = ensureActiveConversation()
            val lang = settings.value.language
            val result = automationManager.executeApprovedAction()
            val msg = when (result) {
                is com.example.automation.AutomationResult.Success -> result.message
                is com.example.automation.AutomationResult.RequiresPermission -> result.guidance
                is com.example.automation.AutomationResult.Failed -> result.reason
            }
            aurixRepository.appendMessage(
                conversationId = convId,
                role = "system_action",
                content = msg,
                inputMode = "ACTION",
                languageCode = lang.code
            )
            _statusBanner.value = msg
        }
    }

    fun cancelPendingConfirmation() {
        scope.launch {
            val convId = ensureActiveConversation()
            automationManager.cancelPendingAction()
            aurixRepository.appendMessage(
                conversationId = convId,
                role = "system_action",
                content = if (settings.value.language == AurixLanguage.ENGLISH) {
                    "Action cancelled by user."
                } else {
                    "ব্যবহারকারীর অনুরোধে অ্যাকশন বাতিল করা হয়েছে।"
                },
                inputMode = "ACTION",
                languageCode = settings.value.language.code
            )
        }
    }

    private fun detectExplicitLanguageSwitch(text: String): AurixLanguage? {
        val lower = text.trim().lowercase()
        return when {
            lower == "speak english" || lower.contains("speak in english") || lower.contains("switch to english") ->
                AurixLanguage.ENGLISH
            lower.contains("বাংলায় কথা বলো") || lower.contains("বাংলায় কথা বলো") ||
                lower.contains("bangla te kotha bolo") || lower.contains("speak bengali") ->
                AurixLanguage.BENGALI
            else -> null
        }
    }

    private fun commitPendingUserTranscriptIfNeeded() {
        val text = synchronized(inputTranscriptBuffer) {
            val value = inputTranscriptBuffer.toString().trim()
            inputTranscriptBuffer.clear()
            value
        }
        _liveUserTranscript.value = ""
        if (text.isNotEmpty()) {
            scope.launch {
                val convId = ensureActiveConversation()
                aurixRepository.appendMessage(
                    conversationId = convId,
                    role = "user",
                    content = text,
                    inputMode = "VOICE",
                    languageCode = settings.value.language.code
                )
            }
        }
    }

    private fun commitPendingAiTranscriptIfNeeded(suffix: String = "") {
        val text = synchronized(outputTranscriptBuffer) {
            val value = outputTranscriptBuffer.toString().trim()
            outputTranscriptBuffer.clear()
            value
        }
        _liveAiTranscript.value = ""
        if (text.isNotEmpty()) {
            scope.launch {
                val convId = ensureActiveConversation()
                aurixRepository.appendMessage(
                    conversationId = convId,
                    role = "model",
                    content = text + suffix,
                    inputMode = "VOICE",
                    languageCode = settings.value.language.code
                )
            }
        }
    }

    fun updateLanguage(language: AurixLanguage) {
        scope.launch {
            settingsRepository.setLanguage(language)
        }
    }

    fun updateVoice(voice: AurixVoice) {
        scope.launch {
            settingsRepository.setVoice(voice)
        }
    }

    fun updateVoiceSpeed(speed: Float) {
        scope.launch {
            settingsRepository.setVoiceSpeed(speed)
            liveAudioEngine.updatePlaybackSettings(speed, settings.value.volume)
        }
    }

    fun updateVolume(volume: Float) {
        scope.launch {
            settingsRepository.setVolume(volume)
            liveAudioEngine.updatePlaybackSettings(settings.value.voiceSpeed, volume)
        }
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setBackgroundServiceEnabled(enabled) }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setMemoryEnabled(enabled) }
    }

    companion object {
        private const val PREFS_MODEL_QUOTA = "aurix_model_quota_prefs"
        private const val KEY_PRIMARY_QUOTA_COOLDOWN_UNTIL = "primary_quota_cooldown_until"
        private const val DEFAULT_PRIMARY_QUOTA_COOLDOWN_MS = 30 * 60 * 1000L
        private const val MAX_PRIMARY_QUOTA_COOLDOWN_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: AurixAssistantEngine? = null

        fun getInstance(context: Context): AurixAssistantEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AurixAssistantEngine(context).also { INSTANCE = it }
            }
        }

        internal fun collectErrorText(e: Throwable?): String {
            if (e == null) return ""
            val sb = StringBuilder()
            var cur: Throwable? = e
            var depth = 0
            while (cur != null && depth < 5) {
                sb.append(cur::class.java.simpleName).append(' ')
                sb.append(cur.message.orEmpty()).append(' ')
                cur = cur.cause
                depth++
            }
            return sb.toString().lowercase()
        }

        internal fun isAppCheckError(e: Throwable?): Boolean {
            if (isQuotaError(e)) return false
            val text = collectErrorText(e)
            return text.contains("app check") ||
                text.contains("appcheck") ||
                text.contains("attestation") ||
                (text.contains("403") && (text.contains("token") || text.contains("forbidden") || text.contains("permission"))) ||
                text.contains("permission_denied") ||
                text.contains("http 403")
        }

        internal fun isQuotaError(e: Throwable?): Boolean {
            val text = collectErrorText(e)
            return text.contains("quotaexceeded") ||
                text.contains("quota") ||
                text.contains("exceeded") ||
                text.contains("rate-limit") ||
                text.contains("rate limit") ||
                text.contains("resource_exhausted") ||
                text.contains("resourceexhausted") ||
                text.contains("too many requests") ||
                text.contains("429")
        }

        internal fun isRetryableModelFailure(e: Throwable?): Boolean {
            val text = collectErrorText(e)
            return isQuotaError(e) ||
                text.contains("not found") ||
                text.contains("404") ||
                text.contains("503") ||
                text.contains("504") ||
                text.contains("unavailable") ||
                text.contains("overloaded") ||
                text.contains("timeout")
        }

        internal fun parseRetryAfterMs(e: Throwable?): Long? {
            val text = collectErrorText(e)
            val match = Regex("retry in\\s+(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?")
                .find(text) ?: return null
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val mins = match.groupValues[2].toLongOrNull() ?: 0L
            val secs = match.groupValues[3].toDoubleOrNull()?.toLong() ?: 0L
            val totalMs = ((hours * 3600L) + (mins * 60L) + secs) * 1000L
            if (totalMs <= 0L) return null
            return totalMs.coerceAtMost(MAX_PRIMARY_QUOTA_COOLDOWN_MS)
        }
    }
}

data class NormalizedTurn(
    val role: String,
    val text: String
)

object ChatHistorySanitizer {
    /**
     * Converts stored Room messages into strictly alternating [user, model, ...] turns
     * suitable for Gemini `startChat(history)` before sending the current user turn.
     */
    fun buildChatHistoryBeforeCurrentPrompt(
        messages: List<MessageEntity>,
        maxTurns: Int = 20
    ): List<Content> {
        val turns = normalizeTurns(
            messages = messages.filter { it.role == "user" || it.role == "model" }.dropLast(1),
            maxMessages = maxTurns
        )
        return turns.map { turn ->
            content(turn.role) { text(turn.text) }
        }
    }

    fun normalizeTurns(
        messages: List<MessageEntity>,
        maxMessages: Int = 20
    ): List<NormalizedTurn> {
        val merged = mutableListOf<NormalizedTurn>()
        for (msg in messages) {
            if (msg.role != "user" && msg.role != "model") continue
            val text = msg.content.trim()
            if (text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role) {
                merged[merged.lastIndex] = last.copy(text = "${last.text}\n$text")
            } else {
                merged.add(NormalizedTurn(msg.role, text))
            }
        }
        val windowed = if (merged.size > maxMessages) merged.takeLast(maxMessages).toMutableList() else merged
        while (windowed.isNotEmpty() && windowed.first().role != "user") {
            windowed.removeAt(0)
        }
        while (windowed.isNotEmpty() && windowed.last().role != "model") {
            windowed.removeAt(windowed.lastIndex)
        }
        return windowed
    }
}
