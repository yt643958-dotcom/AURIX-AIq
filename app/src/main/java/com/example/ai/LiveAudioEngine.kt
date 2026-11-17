package com.example.ai

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerMessage
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "LiveAudioEngine"

private data class PlaybackChunk(
    val generation: Long,
    val sampleRate: Int,
    val pcm: ByteArray
)

/**
 * Bidirectional real-time PCM audio engine for Gemini Live API:
 * - Configures AudioManager MODE_IN_COMMUNICATION & speaker/headset routing for clean hardware AEC
 * - Captures 16kHz 16-bit mono PCM in 20ms frames at THREAD_PRIORITY_AUDIO
 * - Buffers incoming 24kHz PCM chunks through a non-blocking Channel + dedicated playback coroutine
 * - Avoids PlaybackParams resampling distortion at 1.0x speed
 * - Flushes queued audio atomically on server VAD interruption
 */
@OptIn(PublicPreviewAPI::class)
class LiveAudioEngine(
    private val context: Context,
    private val onInputTranscriptChunk: (String) -> Unit,
    private val onOutputTranscriptChunk: (String) -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onInterrupted: () -> Unit,
    private val onToolCall: suspend (FunctionCallPart) -> FunctionResponsePart,
    private val onSessionError: (String) -> Unit,
    private val onSessionClosed: (hadServerResponse: Boolean) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recordJob: Job? = null
    private var receiveJob: Job? = null
    private var playbackJob: Job? = null

    private var playbackChannel: Channel<PlaybackChunk>? = null
    private val playbackGeneration = AtomicLong(0L)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var activeOutputSampleRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    private var savedAudioMode: Int? = null
    private var savedSpeakerphoneOn: Boolean? = null
    private var usingExternalHeadset: Boolean = false

    @Volatile
    private var isMuted: Boolean = false

    @Volatile
    private var currentSpeed: Float = 1.0f

    @Volatile
    private var currentVolume: Float = 0.9f

    @Volatile
    private var lastPlaybackWriteAtMs: Long = 0L

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _aiAmplitude = MutableStateFlow(0f)
    val aiAmplitude: StateFlow<Float> = _aiAmplitude.asStateFlow()

    private val _isAiSpeaking = MutableStateFlow(false)
    val isAiSpeaking: StateFlow<Boolean> = _isAiSpeaking.asStateFlow()

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            _micAmplitude.value = 0f
        }
    }

    fun updatePlaybackSettings(speed: Float, volume: Float) {
        currentSpeed = speed.coerceIn(0.75f, 1.5f)
        currentVolume = volume.coerceIn(0f, 1f)
        val track = audioTrack ?: return
        try {
            track.setVolume(currentVolume)
            applySpeedIfNeeded(track, currentSpeed)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update live AudioTrack playback params: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(session: LiveSession) {
        stopSyncResources()
        configureCommunicationAudioRouting()

        val inputSampleRate = INPUT_SAMPLE_RATE
        val minRecBuf = AudioRecord.getMinBufferSize(
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecBuf * 3
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            restoreAudioRouting()
            onSessionError("Microphone initialization failed. Check RECORD_AUDIO permission.")
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            autoGainControl = runCatching {
                AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        val initialTrack = createOutputTrack(DEFAULT_OUTPUT_SAMPLE_RATE)
        activeOutputSampleRate = DEFAULT_OUTPUT_SAMPLE_RATE
        audioRecord = recorder
        audioTrack = initialTrack

        val queue = Channel<PlaybackChunk>(Channel.UNLIMITED)
        playbackChannel = queue
        playbackGeneration.set(1L)

        recorder.startRecording()
        initialTrack.play()

        // Dedicated non-blocking audio playback worker with initial jitter pre-buffering
        playbackJob = scope.launch {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val prebuffer = ByteArrayOutputStream(8192)
            var needsPrebuffer = true
            var lastAiAmpEmitMs = 0L

            try {
                while (isActive) {
                    val firstChunk = queue.receive()
                    val currentGen = playbackGeneration.get()
                    if (firstChunk.generation != currentGen) {
                        prebuffer.reset()
                        needsPrebuffer = true
                        continue
                    }

                    // Rebuild AudioTrack cleanly if server sample rate differs
                    var track = ensureTrackForSampleRate(firstChunk.sampleRate)

                    if (needsPrebuffer) {
                        prebuffer.reset()
                        prebuffer.write(firstChunk.pcm)
                        // Briefly coalesce tiny initial network packets (~55ms / ~3.8KB @ 24kHz) so first word doesn't underrun
                        val targetBytes = (firstChunk.sampleRate * 2 * PREBUFFER_MS) / 1000
                        val deadlineMs = System.currentTimeMillis() + PREBUFFER_MS
                        while (prebuffer.size() < targetBytes) {
                            val remaining = deadlineMs - System.currentTimeMillis()
                            if (remaining <= 0L) break
                            val next = withTimeoutOrNull(remaining) { queue.receiveCatching().getOrNull() }
                                ?: break
                            if (next.generation != playbackGeneration.get()) {
                                prebuffer.reset()
                                break
                            }
                            if (next.sampleRate != activeOutputSampleRate) {
                                flushBytesToTrack(track, prebuffer.toByteArray())
                                prebuffer.reset()
                                track = ensureTrackForSampleRate(next.sampleRate)
                            }
                            prebuffer.write(next.pcm)
                        }
                        if (firstChunk.generation == playbackGeneration.get() && prebuffer.size() > 0) {
                            val combined = prebuffer.toByteArray()
                            prebuffer.reset()
                            lastPlaybackWriteAtMs = System.currentTimeMillis()
                            writeAudioChunk(track, combined)
                            lastAiAmpEmitMs = emitAiAmplitudeThrottled(combined, lastAiAmpEmitMs)
                            needsPrebuffer = false
                        }
                    } else {
                        lastPlaybackWriteAtMs = System.currentTimeMillis()
                        writeAudioChunk(track, firstChunk.pcm)
                        lastAiAmpEmitMs = emitAiAmplitudeThrottled(firstChunk.pcm, lastAiAmpEmitMs)
                    }

                    // Drain already-queued consecutive chunks smoothly
                    while (isActive) {
                        val queued = queue.tryReceive().getOrNull() ?: break
                        if (queued.generation != playbackGeneration.get()) continue
                        track = ensureTrackForSampleRate(queued.sampleRate)
                        lastPlaybackWriteAtMs = System.currentTimeMillis()
                        writeAudioChunk(track, queued.pcm)
                        lastAiAmpEmitMs = emitAiAmplitudeThrottled(queued.pcm, lastAiAmpEmitMs)
                    }

                    if (queue.isEmpty && System.currentTimeMillis() - lastPlaybackWriteAtMs > 180L) {
                        needsPrebuffer = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playback loop stopped: ${e.message}")
            }
        }

        // Capture loop -> stream 20ms 16kHz PCM frames to Gemini Live API
        recordJob = scope.launch {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val chunkBytes = 640 // 20ms @ 16kHz 16-bit mono (low jitter)
            val buffer = ByteArray(chunkBytes)
            val silenceFrame = ByteArray(chunkBytes)
            var lastMicAmpEmitMs = 0L

            try {
                while (isActive && !session.isClosed()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (!isMuted) {
                            val rms = computeNormalizedRms(buffer, read)
                            val now = System.currentTimeMillis()
                            if (now - lastMicAmpEmitMs >= AMP_EMIT_INTERVAL_MS) {
                                _micAmplitude.value = rms
                                lastMicAmpEmitMs = now
                            }

                            // On phone speakerphone, suppress faint speaker echo while AI audio is actively outputting
                            // so Gemini VAD doesn't self-interrupt mid-sentence; direct user speech (> threshold) still passes!
                            val aiActivelyPlaying = _isAiSpeaking.value &&
                                (now - lastPlaybackWriteAtMs < ECHO_TAIL_GUARD_MS)
                            val sendSilenceForEcho = aiActivelyPlaying &&
                                !usingExternalHeadset &&
                                rms < SPEAKER_ECHO_BARGE_IN_THRESHOLD

                            val payload = when {
                                sendSilenceForEcho && read == chunkBytes -> silenceFrame
                                sendSilenceForEcho -> ByteArray(read)
                                read == buffer.size -> buffer.copyOf()
                                else -> buffer.copyOf(read)
                            }
                            session.sendAudioRealtime(
                                InlineData(payload, "audio/pcm;rate=16000")
                            )
                        } else {
                            _micAmplitude.value = 0f
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture stream error", e)
            }
        }

        // Receive loop -> non-blocking dispatch of PCM audio to playbackQueue + transcripts + tool calls
        receiveJob = scope.launch {
            var receivedServerMessage = false
            try {
                session.receive().collect { message: LiveServerMessage ->
                    receivedServerMessage = true
                    when (message) {
                        is LiveServerContent -> {
                            if (message.interrupted) {
                                val nextGen = playbackGeneration.incrementAndGet()
                                drainChannel(queue)
                                audioTrack?.let { flushPlayback(it) }
                                _isAiSpeaking.value = false
                                _aiAmplitude.value = 0f
                                Log.d(TAG, "Live playback interrupted & flushed (gen=$nextGen)")
                                onInterrupted()
                            }

                            message.inputTranscription?.text?.let { text ->
                                if (text.isNotEmpty()) onInputTranscriptChunk(text)
                            }

                            message.outputTranscription?.text?.let { text ->
                                if (text.isNotEmpty()) onOutputTranscriptChunk(text)
                            }

                            val gen = playbackGeneration.get()
                            val parts = message.content?.parts.orEmpty()
                            for (part in parts) {
                                if (part is InlineDataPart && part.mimeType.startsWith("audio/pcm")) {
                                    val pcm = part.inlineData
                                    if (pcm.isNotEmpty()) {
                                        _isAiSpeaking.value = true
                                        val sampleRate = parseSampleRate(part.mimeType)
                                        queue.trySend(
                                            PlaybackChunk(
                                                generation = gen,
                                                sampleRate = sampleRate,
                                                pcm = pcm
                                            )
                                        )
                                    }
                                }
                            }

                            if (message.turnComplete || message.generationComplete) {
                                _isAiSpeaking.value = false
                                _aiAmplitude.value = 0f
                                if (message.turnComplete) {
                                    onTurnComplete()
                                }
                            }
                        }

                        is LiveServerToolCall -> {
                            val responses = message.functionCalls.map { call ->
                                onToolCall(call)
                            }
                            if (responses.isNotEmpty()) {
                                session.sendFunctionResponse(responses)
                            }
                        }

                        is LiveServerToolCallCancellation -> {
                            Log.d(TAG, "Tool calls cancelled: ${message.functionIds}")
                        }

                        is LiveServerGoAway -> {
                            Log.w(TAG, "Live session GoAway received; connection nearing time limit.")
                        }
                    }
                }
                onSessionClosed(receivedServerMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live session receive stream failed", e)
                onSessionError(e.localizedMessage ?: "Live voice stream disconnected.")
            } finally {
                _isAiSpeaking.value = false
                _aiAmplitude.value = 0f
                _micAmplitude.value = 0f
            }
        }
    }

    private fun createOutputTrack(sampleRate: Int): AudioTrack {
        val minPlayBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        // At least 1 full second buffer (sampleRate * 2 bytes) or 8x minBufferSize to prevent network jitter underruns
        val targetBufBytes = maxOf(minPlayBuf * 8, sampleRate * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(targetBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.setVolume(currentVolume)
        applySpeedIfNeeded(track, currentSpeed)
        return track
    }

    private fun ensureTrackForSampleRate(sampleRate: Int): AudioTrack {
        val current = audioTrack
        if (current != null && activeOutputSampleRate == sampleRate && current.state == AudioTrack.STATE_INITIALIZED) {
            return current
        }
        Log.i(TAG, "Reconfiguring AudioTrack sampleRate: $activeOutputSampleRate -> $sampleRate")
        try {
            current?.pause()
            current?.flush()
            current?.release()
        } catch (_: Exception) {
        }
        val replacement = createOutputTrack(sampleRate)
        replacement.play()
        activeOutputSampleRate = sampleRate
        audioTrack = replacement
        return replacement
    }

    private fun applySpeedIfNeeded(track: AudioTrack, speed: Float) {
        // Avoid Sonic timestretcher artifacts on streaming chunks when using normal 1.0x speed
        if (abs(speed - 1.0f) <= 0.02f) return
        try {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.playbackParams = PlaybackParams().setSpeed(speed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PlaybackParams speed setup ignored: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun configureCommunicationAudioRouting() {
        val am = audioManager ?: return
        try {
            if (savedAudioMode == null) {
                savedAudioMode = am.mode
            }
            if (savedSpeakerphoneOn == null) {
                savedSpeakerphoneOn = am.isSpeakerphoneOn
            }
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            val headsetConnected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            usingExternalHeadset = headsetConnected

            if (!headsetConnected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        am.setCommunicationDevice(speaker)
                    } else {
                        am.isSpeakerphoneOn = true
                    }
                } else {
                    am.isSpeakerphoneOn = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager routing setup failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioRouting() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            savedSpeakerphoneOn?.let { am.isSpeakerphoneOn = it }
            savedAudioMode?.let { am.mode = it }
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager routing restore failed: ${e.message}")
        } finally {
            savedAudioMode = null
            savedSpeakerphoneOn = null
            usingExternalHeadset = false
        }
    }

    private fun flushBytesToTrack(track: AudioTrack, pcm: ByteArray) {
        if (pcm.isNotEmpty()) {
            writeAudioChunk(track, pcm)
        }
    }

    private fun writeAudioChunk(track: AudioTrack, pcm: ByteArray) {
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack write error: ${e.message}")
        }
    }

    private fun flushPlayback(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack flush error: ${e.message}")
        }
    }

    private fun drainChannel(channel: Channel<PlaybackChunk>) {
        while (channel.tryReceive().isSuccess) {
            // discard stale pre-interruption frames
        }
    }

    private fun emitAiAmplitudeThrottled(pcm: ByteArray, lastEmitMs: Long): Long {
        val now = System.currentTimeMillis()
        if (now - lastEmitMs >= AMP_EMIT_INTERVAL_MS) {
            _aiAmplitude.value = computeNormalizedRms(pcm, pcm.size)
            return now
        }
        return lastEmitMs
    }

    suspend fun stop() {
        playbackGeneration.incrementAndGet()
        playbackChannel?.close()
        recordJob?.cancelAndJoin()
        receiveJob?.cancelAndJoin()
        playbackJob?.cancelAndJoin()
        recordJob = null
        receiveJob = null
        playbackJob = null
        playbackChannel = null
        stopSyncResources()
    }

    fun stopSyncResources() {
        playbackGeneration.incrementAndGet()
        playbackChannel?.close()
        playbackChannel = null
        _micAmplitude.value = 0f
        _aiAmplitude.value = 0f
        _isAiSpeaking.value = false

        runCatching { echoCanceler?.release() }
        echoCanceler = null

        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        runCatching { autoGainControl?.release() }
        autoGainControl = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null

        restoreAudioRouting()
    }

    private fun computeNormalizedRms(pcm: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val sample = (high shl 8) or low
            val norm = sample / 32768.0
            sumSquares += norm * norm
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSquares / samples).toFloat()
        return (rms * 3.2f).coerceIn(0f, 1f)
    }

    companion object {
        private const val INPUT_SAMPLE_RATE = 16000
        private const val DEFAULT_OUTPUT_SAMPLE_RATE = 24000
        private const val PREBUFFER_MS = 60
        private const val AMP_EMIT_INTERVAL_MS = 35L
        private const val ECHO_TAIL_GUARD_MS = 280L
        private const val SPEAKER_ECHO_BARGE_IN_THRESHOLD = 0.14f

        internal fun parseSampleRate(mimeType: String): Int {
            val match = Regex("rate=(\\d+)").find(mimeType.lowercase())
            val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            return if (parsed != null && parsed in 8000..48000) parsed else DEFAULT_OUTPUT_SAMPLE_RATE
        }
    }
}
