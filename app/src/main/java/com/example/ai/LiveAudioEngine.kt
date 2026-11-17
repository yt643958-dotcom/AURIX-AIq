package com.example.ai

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

private const val TAG = "LiveAudioEngine"

/**
 * Bidirectional real-time PCM audio engine for Gemini Live API:
 * - Captures 16kHz 16-bit mono PCM via AudioRecord (with AEC/NS when available)
 * - Streams to LiveSession via sendAudioRealtime
 * - Plays 24kHz 16-bit mono PCM via AudioTrack with dynamic speed & volume
 * - Immediately flushes queued audio on server VAD interruption
 * - Emits live mic & AI RMS amplitude levels for visualizers
 */
@OptIn(PublicPreviewAPI::class)
class LiveAudioEngine(
    private val onInputTranscriptChunk: (String) -> Unit,
    private val onOutputTranscriptChunk: (String) -> Unit,
    private val onTurnComplete: () -> Unit,
    private val onInterrupted: () -> Unit,
    private val onToolCall: suspend (FunctionCallPart) -> FunctionResponsePart,
    private val onSessionError: (String) -> Unit,
    private val onSessionClosed: (hadServerResponse: Boolean) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recordJob: Job? = null
    private var receiveJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var isMuted: Boolean = false

    @Volatile
    private var currentSpeed: Float = 1.0f

    @Volatile
    private var currentVolume: Float = 0.9f

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
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING || track.state == AudioTrack.STATE_INITIALIZED) {
                track.playbackParams = PlaybackParams().setSpeed(currentSpeed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update live AudioTrack playback params: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(session: LiveSession) {
        stopSyncResources()

        val inputSampleRate = 16000
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
            minRecBuf * 2
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onSessionError("Microphone initialization failed. Check RECORD_AUDIO permission.")
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                enabled = true
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                enabled = true
            }
        }

        val outputSampleRate = 24000
        val minPlayBuf = AudioTrack.getMinBufferSize(
            outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minPlayBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.setVolume(currentVolume)
        try {
            track.playbackParams = PlaybackParams().setSpeed(currentSpeed)
        } catch (e: Exception) {
            Log.w(TAG, "PlaybackParams speed setup ignored: ${e.message}")
        }

        audioRecord = recorder
        audioTrack = track

        recorder.startRecording()
        track.play()

        // Capture loop -> stream 16kHz PCM chunks to Gemini Live API
        recordJob = scope.launch {
            val chunkBytes = 1280 // 40ms @ 16kHz 16-bit mono
            val buffer = ByteArray(chunkBytes)
            try {
                while (isActive && !session.isClosed()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (!isMuted) {
                            _micAmplitude.value = computeNormalizedRms(buffer, read)
                            val payload = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
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

        // Receive loop -> play 24kHz PCM, emit transcriptions, handle VAD interruptions & tool calls
        receiveJob = scope.launch {
            var receivedServerMessage = false
            try {
                session.receive().collect { message: LiveServerMessage ->
                    receivedServerMessage = true
                    when (message) {
                        is LiveServerContent -> {
                            if (message.interrupted) {
                                flushPlayback(track)
                                _isAiSpeaking.value = false
                                _aiAmplitude.value = 0f
                                onInterrupted()
                            }

                            message.inputTranscription?.text?.let { text ->
                                if (text.isNotEmpty()) onInputTranscriptChunk(text)
                            }

                            message.outputTranscription?.text?.let { text ->
                                if (text.isNotEmpty()) onOutputTranscriptChunk(text)
                            }

                            val parts = message.content?.parts.orEmpty()
                            for (part in parts) {
                                if (part is InlineDataPart && part.mimeType.startsWith("audio/pcm")) {
                                    val pcm = part.inlineData
                                    if (pcm.isNotEmpty()) {
                                        _isAiSpeaking.value = true
                                        _aiAmplitude.value = computeNormalizedRms(pcm, pcm.size)
                                        writeAudioChunk(track, pcm)
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

    suspend fun stop() {
        recordJob?.cancelAndJoin()
        receiveJob?.cancelAndJoin()
        recordJob = null
        receiveJob = null
        stopSyncResources()
    }

    fun stopSyncResources() {
        _micAmplitude.value = 0f
        _aiAmplitude.value = 0f
        _isAiSpeaking.value = false

        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
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
}
