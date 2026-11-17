package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.example.ai.AurixAssistantEngine
import com.example.ai.TextChatState
import com.example.ai.VoiceSessionState
import com.example.automation.AurixAccessibilityService
import com.example.automation.AutomationResult
import com.example.automation.PendingActionConfirmation
import com.example.automation.VisibleScreenSnapshot
import com.example.core.AppCheckStateHolder
import com.example.core.AppCheckStatus
import com.example.core.AurixLanguage
import com.example.core.AurixVoice
import com.example.data.local.AurixSettings
import com.example.data.local.ConversationEntity
import com.example.data.local.MemoryFactEntity
import com.example.data.local.MessageEntity
import com.example.service.AurixAssistantService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PermissionReadiness(
    val micGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationsGranted: Boolean = true
)

class AurixViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = AurixAssistantEngine.getInstance(application)

    val settings: StateFlow<AurixSettings> = engine.settings
    val conversations: StateFlow<List<ConversationEntity>> = engine.conversations
    val activeConversationId: StateFlow<Long> = engine.activeConversationId
    val messages: StateFlow<List<MessageEntity>> = engine.messages
    val memoryFacts: StateFlow<List<MemoryFactEntity>> = engine.memoryFacts
    val totalMessageCount: StateFlow<Int> = engine.totalMessageCount

    val voiceState: StateFlow<VoiceSessionState> = engine.voiceState
    val isMuted: StateFlow<Boolean> = engine.isMuted
    val micAmplitude: StateFlow<Float> = engine.micAmplitude
    val aiAmplitude: StateFlow<Float> = engine.aiAmplitude
    val liveUserTranscript: StateFlow<String> = engine.liveUserTranscript
    val liveAiTranscript: StateFlow<String> = engine.liveAiTranscript
    val textChatState: StateFlow<TextChatState> = engine.textChatState
    val statusBanner: StateFlow<String?> = engine.statusBanner
    val appCheckStatus: StateFlow<AppCheckStatus> = engine.appCheckStatus
    val appCheckDiagnostic: StateFlow<String> = engine.appCheckDiagnostic
    val pendingConfirmation: StateFlow<PendingActionConfirmation?> = engine.pendingConfirmation
    val accessibilityConnected: StateFlow<Boolean> = engine.accessibilityConnected
    val lastScreenSnapshot: StateFlow<VisibleScreenSnapshot?> = AurixAccessibilityService.lastSnapshot

    private val _permissions = MutableStateFlow(PermissionReadiness())
    val permissions: StateFlow<PermissionReadiness> = _permissions.asStateFlow()

    init {
        refreshPermissions()
        engine.refreshAppCheckStatus()
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val mic = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val overlay = Settings.canDrawOverlays(ctx)
        val a11y = AurixAccessibilityService.isConnected.value

        _permissions.value = PermissionReadiness(
            micGranted = mic,
            overlayGranted = overlay,
            accessibilityEnabled = a11y,
            notificationsGranted = notif
        )
    }

    fun startVoiceConversation() = engine.startVoiceConversation()
    fun stopVoiceConversation() = engine.stopVoiceConversation()
    fun toggleMute() = engine.toggleMute()
    fun sendTextMessage(text: String) = engine.sendTextMessage(text)
    fun retryLastMessage() = engine.retryLastMessage()
    fun clearTextChatError() = engine.clearTextChatError()
    fun clearStatusBanner() = engine.clearStatusBanner()
    fun onMicPermissionDenied() = engine.notifyMicPermissionDenied()

    fun startNewConversation() = engine.startNewConversation()
    fun selectConversation(id: Long) = engine.selectConversation(id)
    fun deleteConversation(id: Long) = engine.deleteConversation(id)
    fun clearAllHistory() = engine.clearAllHistory()

    fun addMemoryFact(fact: String) = engine.addMemoryFact(fact)
    fun deleteMemoryFact(id: Long) = engine.deleteMemoryFact(id)
    fun clearAllMemories() = engine.clearAllMemories()

    fun updateLanguage(language: AurixLanguage) = engine.updateLanguage(language)
    fun updateVoice(voice: AurixVoice) = engine.updateVoice(voice)
    fun updateVoiceSpeed(speed: Float) = engine.updateVoiceSpeed(speed)
    fun updateVolume(volume: Float) = engine.updateVolume(volume)
    fun setMemoryEnabled(enabled: Boolean) = engine.setMemoryEnabled(enabled)

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        engine.setFloatingBubbleEnabled(enabled)
        if (!enabled) {
            AurixAssistantService.startServiceAction(
                getApplication(),
                AurixAssistantService.ACTION_HIDE_BUBBLE
            )
        }
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        engine.setBackgroundServiceEnabled(enabled)
        val ctx = getApplication<Application>()
        if (enabled && _permissions.value.micGranted) {
            AurixAssistantService.startServiceAction(ctx, null)
        } else if (!enabled) {
            AurixAssistantService.startServiceAction(ctx, AurixAssistantService.ACTION_STOP_SERVICE)
        }
    }

    fun launchCompactOverlayNow() {
        val ctx = getApplication<Application>()
        if (Settings.canDrawOverlays(ctx)) {
            AurixAssistantService.startServiceAction(ctx, AurixAssistantService.ACTION_OPEN_COMPACT)
        } else {
            openOverlayPermissionSettings()
        }
    }

    fun openOverlayPermissionSettings() = engine.automationManager.openOverlaySettings()
    fun openAccessibilityPermissionSettings() = engine.automationManager.openAccessibilitySettings()
    fun openFacebookOwnerProfile() = engine.automationManager.openUrl(settings.value.officialFacebookUrl)

    fun launchAppShortcut(appName: String): AutomationResult =
        engine.automationManager.openApp(appName)

    fun openPhoneSettingsShortcut(settingType: String): AutomationResult =
        engine.automationManager.openPhoneSettings(settingType)

    fun captureScreenNow(): AutomationResult =
        engine.automationManager.readVisibleScreen()

    fun updatePendingProposedText(text: String) = engine.automationManager.updatePendingText(text)
    fun approvePendingConfirmation() = engine.approvePendingConfirmation()
    fun cancelPendingConfirmation() = engine.cancelPendingConfirmation()

    fun getAppCheckDiagnostic(): String = AppCheckStateHolder.lastDiagnostic
}
