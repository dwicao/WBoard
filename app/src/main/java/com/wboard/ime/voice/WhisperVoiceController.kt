package com.wboard.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 100% self-contained, offline Indonesian voice input controller using Android AudioRecord
 * + local energy VAD + whisper.cpp JNI (WhisperEngine). Zero reliance on external OEM/Google
 * SpeechRecognizer services.
 */
class WhisperVoiceController(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onVoiceListeningStarted()
        fun onVoiceRmsChanged(level: Float)
        fun onVoiceTranscribing()
        fun onVoiceFinalResult(text: String)
        fun onVoiceError(reason: String)
        fun onVoiceStopped()
    }

    private val engine = WhisperEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    fun isActive(): Boolean = recording.get()

    @SuppressLint("MissingPermission")
    fun startListening(
        preferredModelId: String?,
        applyPunctuation: Boolean,
        capitalizeFirst: Boolean
    ): Boolean {
        if (!IndonesianVoiceController.hasAudioPermission(context)) {
            listener.onVoiceError("no_permission")
            return false
        }

        val activeModel = WhisperModelManager.activeModelFile(context, preferredModelId)
        if (activeModel == null) {
            listener.onVoiceError("no_model")
            return false
        }

        if (recording.get()) return true

        val sampleRate = WhisperAudioProcessor.SAMPLE_RATE_HZ
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 2)

        val audioRecord = createAudioRecord(sampleRate, minBufBytes)
        if (audioRecord == null) {
            listener.onVoiceError("audio_init")
            return false
        }

        cancelled.set(false)
        recording.set(true)
        listener.onVoiceListeningStarted()

        val modelPath = activeModel.second.absolutePath
        val maxSamples = sampleRate * MAX_DURATION_SEC
        val buffer = FloatArray(maxSamples)

        // Pre-warm Whisper model while user starts speaking
        val preloadFuture = worker.submit<Boolean> { engine.ensureLoaded(modelPath) }

        recordingThread = Thread({
            var sampleCount = 0
            val chunk = ShortArray(1024)
            var speechDetected = false
            var silenceFrames = 0
            var totalFrames = 0

            try {
                audioRecord.startRecording()
                while (recording.get() && !cancelled.get() && sampleCount < maxSamples) {
                    val read = audioRecord.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val copyLen = min(read, maxSamples - sampleCount)
                        for (i in 0 until copyLen) {
                            buffer[sampleCount + i] = chunk[i] / 32768.0f
                        }
                        sampleCount += copyLen

                        val rms = WhisperAudioProcessor.computeNormalizedRms(chunk, read)
                        mainHandler.post {
                            if (recording.get()) listener.onVoiceRmsChanged(rms)
                        }

                        totalFrames++
                        if (rms >= SPEECH_THRESHOLD) {
                            speechDetected = true
                            silenceFrames = 0
                        } else if (speechDetected) {
                            silenceFrames++
                            // Auto-endpoint after ~1.45s of silence following speech
                            if (silenceFrames >= ENDPOINT_SILENCE_FRAMES) {
                                break
                            }
                        } else if (totalFrames >= INITIAL_TIMEOUT_FRAMES) {
                            // ~6s without any speech
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (_: Throwable) {
                if (!cancelled.get()) {
                    mainHandler.post { listener.onVoiceError("audio_read") }
                }
            } finally {
                recording.set(false)
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
            }

            if (cancelled.get()) {
                mainHandler.post { listener.onVoiceStopped() }
                return@Thread
            }

            val trimmed = WhisperAudioProcessor.trimSilence(buffer.copyOf(sampleCount))
            if (trimmed.isEmpty()) {
                mainHandler.post { listener.onVoiceStopped() }
                return@Thread
            }

            mainHandler.post { listener.onVoiceTranscribing() }

            worker.execute {
                val loaded = runCatching { preloadFuture.get() }.getOrDefault(false)
                if (!loaded) {
                    mainHandler.post { listener.onVoiceError("model_load") }
                    return@execute
                }
                val rawText = engine.transcribeIndonesian(trimmed)
                val processed = IndonesianVoicePostProcessor.process(
                    rawText = rawText,
                    applyPunctuationCommands = applyPunctuation,
                    capitalizeFirst = capitalizeFirst
                )
                mainHandler.post {
                    if (processed.isNotEmpty()) {
                        listener.onVoiceFinalResult(processed)
                    } else {
                        listener.onVoiceStopped()
                    }
                }
            }
        }, "WBoard-AudioCapture").apply { start() }

        return true
    }

    fun stopAndTranscribe() {
        recording.set(false)
    }

    fun cancel() {
        cancelled.set(true)
        recording.set(false)
    }

    fun destroy() {
        cancel()
        worker.execute { engine.release() }
        worker.shutdown()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(sampleRate: Int, bufferSizeBytes: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes * 2
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }

    companion object {
        private const val MAX_DURATION_SEC = 20
        private const val SPEECH_THRESHOLD = 0.055f
        private const val ENDPOINT_SILENCE_FRAMES = 23 // ~1.47s at 1024 samples / 16kHz (64ms/frame)
        private const val INITIAL_TIMEOUT_FRAMES = 94  // ~6.0s
    }
}
