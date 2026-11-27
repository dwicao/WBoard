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
 * Supports:
 * 1. Android 12+ on-device recognizer (required when strictOffline is enabled)
 * 2. Automatic fallback to system RecognitionService with EXTRA_PREFER_OFFLINE
 * 3. Retry without offline-only constraint when offline id-ID pack is missing & strictOffline is off
 * 4. Activity fallback signal when an OEM blocks SpeechRecognizer inside InputMethodService
 */
class IndonesianVoiceController(
    private val context: Context,
    private val listener: Listener
) {

    enum class Availability {
        ON_DEVICE_READY,
        SYSTEM_ONLY,
        ACTIVITY_ONLY,
        UNAVAILABLE
    }

    private enum class Mode {
        ON_DEVICE,
        SYSTEM_PREFER_OFFLINE,
        SYSTEM_STANDARD
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
    private var strictOfflineMode: Boolean = false
    private var punctuationEnabled: Boolean = true
    private var capitalizeStart: Boolean = false
    private var currentMode: Mode = Mode.SYSTEM_PREFER_OFFLINE
    private var receivedPartial: Boolean = false

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

        strictOfflineMode = strictOffline
        punctuationEnabled = applyPunctuation
        capitalizeStart = capitalizeFirst
        receivedPartial = false

        val initialMode = resolveInitialMode(strictOffline)
        if (initialMode == null) {
            if (!strictOffline && isActivityAvailable(context)) {
                listener.onVoiceError("fallback_activity")
            } else {
                listener.onVoiceError("offline_unavailable")
            }
            return false
        }

        return beginWithMode(initialMode)
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

    private fun resolveInitialMode(strictOffline: Boolean): Mode? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            return Mode.ON_DEVICE
        }
        if (strictOffline) {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                SpeechRecognizer.isRecognitionAvailable(context)
            ) {
                Mode.SYSTEM_PREFER_OFFLINE
            } else {
                null
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            return Mode.SYSTEM_PREFER_OFFLINE
        }
        return null
    }

    private fun beginWithMode(mode: Mode): Boolean {
        stopAndReleaseInternal()
        val created = createForMode(mode)
        if (created == null) {
            if (!strictOfflineMode && isActivityAvailable(context)) {
                listener.onVoiceError("fallback_activity")
            } else {
                listener.onVoiceError("offline_unavailable")
            }
            return false
        }

        currentMode = mode
        recognizer = created
        created.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALE_ID)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LOCALE_ID)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (mode == Mode.ON_DEVICE || mode == Mode.SYSTEM_PREFER_OFFLINE) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        return try {
            isListening = true
            created.startListening(intent)
            listener.onListeningStarted(mode == Mode.ON_DEVICE)
            true
        } catch (t: Throwable) {
            isListening = false
            stopAndReleaseInternal()
            if (!strictOfflineMode && isActivityAvailable(context)) {
                listener.onVoiceError("fallback_activity")
            } else {
                listener.onVoiceError(t.message ?: "start_failed")
            }
            false
        }
    }

    private fun createForMode(mode: Mode): SpeechRecognizer? {
        return when (mode) {
            Mode.ON_DEVICE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    null
                }
            }
            Mode.SYSTEM_PREFER_OFFLINE, Mode.SYSTEM_STANDARD -> {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } else {
                    null
                }
            }
        }
    }

    private fun nextFallbackMode(error: Int): Mode? {
        if (strictOfflineMode || receivedPartial) return null
        val isRecoverableSetupError = error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error >= 10 // ERROR_SERVER_DISCONNECTED (11), ERROR_LANGUAGE_NOT_SUPPORTED (12), ERROR_LANGUAGE_UNAVAILABLE (13), ERROR_CANNOT_CHECK_SUPPORT (14)
        if (!isRecoverableSetupError) return null

        return when (currentMode) {
            Mode.ON_DEVICE -> {
                if (SpeechRecognizer.isRecognitionAvailable(context)) Mode.SYSTEM_PREFER_OFFLINE else null
            }
            Mode.SYSTEM_PREFER_OFFLINE -> {
                if (error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error >= 12
                ) {
                    Mode.SYSTEM_STANDARD
                } else {
                    null
                }
            }
            Mode.SYSTEM_STANDARD -> null
        }
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
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            listener.onRmsChanged(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!isListening) return
            val fallback = nextFallbackMode(error)
            if (fallback != null) {
                beginWithMode(fallback)
                return
            }

            isListening = false
            stopAndReleaseInternal()

            if (!strictOfflineMode && !receivedPartial &&
                (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error >= 11) &&
                isActivityAvailable(context)
            ) {
                listener.onVoiceError("fallback_activity")
                return
            }

            val reason = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "audio"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_permission"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "offline"
                SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                12, 13, 14 -> "offline_unavailable"
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
                receivedPartial = true
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

        fun isActivityAvailable(context: Context): Boolean =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .resolveActivity(context.packageManager) != null

        fun checkAvailability(context: Context): Availability {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                return Availability.ON_DEVICE_READY
            }
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                return Availability.SYSTEM_ONLY
            }
            if (isActivityAvailable(context)) {
                return Availability.ACTIVITY_ONLY
            }
            return Availability.UNAVAILABLE
        }
    }
}
