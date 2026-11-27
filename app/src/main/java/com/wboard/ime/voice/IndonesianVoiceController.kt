package com.wboard.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Native in-app Indonesian (id-ID) voice input controller built on Android's SpeechRecognizer.
 * Enforces on-device offline recognition on Android 12+ when strictOffline is enabled.
 */
class IndonesianVoiceController(
    private val context: Context,
    private val listener: Listener
) {

    enum class Availability {
        ON_DEVICE_READY,
        SYSTEM_ONLY,
        UNAVAILABLE
    }

    interface Listener {
        fun onListeningStarted(onDevice: Boolean)
        fun onRmsChanged(level: Float)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onVoiceError(reason: String)
        fun onVoiceStopped()
    }

    private var recognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false
    private var punctuationEnabled: Boolean = true
    private var capitalizeStart: Boolean = false
    private var usingOnDevice: Boolean = false

    fun isActive(): Boolean = isListening

    fun startListening(
        strictOffline: Boolean,
        applyPunctuation: Boolean,
        capitalizeFirst: Boolean
    ): Boolean {
        if (!hasAudioPermission(context)) {
            listener.onVoiceError("no_permission")
            return false
        }

        stopAndReleaseInternal()
        punctuationEnabled = applyPunctuation
        capitalizeStart = capitalizeFirst

        val created = createRecognizer(strictOffline)
        if (created == null) {
            listener.onVoiceError("offline_unavailable")
            return false
        }

        recognizer = created.first
        usingOnDevice = created.second
        created.first.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALE_ID)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LOCALE_ID)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        return try {
            isListening = true
            created.first.startListening(intent)
            listener.onListeningStarted(usingOnDevice)
            true
        } catch (t: Throwable) {
            isListening = false
            stopAndReleaseInternal()
            listener.onVoiceError(t.message ?: "start_failed")
            false
        }
    }

    fun stopListening() {
        if (!isListening) return
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        isListening = false
        stopAndReleaseInternal()
        listener.onVoiceStopped()
    }

    fun destroy() {
        isListening = false
        stopAndReleaseInternal()
    }

    private fun createRecognizer(strictOffline: Boolean): Pair<SpeechRecognizer, Boolean>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context) to true
            }
            if (strictOffline) {
                return null
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            return SpeechRecognizer.createSpeechRecognizer(context) to false
        }
        return null
    }

    private fun stopAndReleaseInternal() {
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!isListening) return
            // Map typical speech rmsdB [-2, 10] smoothly into [0, 1]
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            listener.onRmsChanged(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // Waiting for final results callback
        }

        override fun onError(error: Int) {
            if (!isListening) return
            isListening = false
            stopAndReleaseInternal()
            val reason = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "audio"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_permission"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "offline"
                SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "offline_unavailable"
                else -> "code_$error"
            }
            listener.onVoiceError(reason)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val raw = matches?.firstOrNull().orEmpty()
            stopAndReleaseInternal()
            val processed = IndonesianVoicePostProcessor.process(
                rawText = raw,
                applyPunctuationCommands = punctuationEnabled,
                capitalizeFirst = capitalizeStart
            )
            if (processed.isNotEmpty()) {
                listener.onFinalResult(processed)
            } else {
                listener.onVoiceStopped()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isListening) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val raw = matches?.firstOrNull().orEmpty()
            if (raw.isNotEmpty()) {
                val processed = IndonesianVoicePostProcessor.process(
                    rawText = raw,
                    applyPunctuationCommands = punctuationEnabled,
                    capitalizeFirst = capitalizeStart
                )
                listener.onPartialResult(processed)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val LOCALE_ID = "id-ID"

        fun hasAudioPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        fun checkAvailability(context: Context): Availability {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                return Availability.ON_DEVICE_READY
            }
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                return Availability.SYSTEM_ONLY
            }
            return Availability.UNAVAILABLE
        }
    }
}
