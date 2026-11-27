package com.wboard.ime.voice

/**
 * JNI bridge to whisper.cpp compiled natively into libwboard_whisper.so.
 * Caches the loaded whisper_context across dictation sessions for instant response.
 */
class WhisperEngine {

    private var ctxPtr: Long = 0L
    private var loadedModelPath: String? = null

    @Synchronized
    fun ensureLoaded(modelPath: String): Boolean {
        if (ctxPtr != 0L && loadedModelPath == modelPath) {
            return true
        }
        release()
        val ptr = nativeInit(modelPath)
        if (ptr == 0L) return false
        ctxPtr = ptr
        loadedModelPath = modelPath
        return true
    }

    @Synchronized
    fun transcribeIndonesian(samples: FloatArray): String {
        val ptr = ctxPtr
        if (ptr == 0L || samples.isEmpty()) return ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val raw = nativeTranscribe(
            ctxPtr = ptr,
            numThreads = threads,
            audioData = samples,
            language = "id",
            initialPrompt = INDONESIAN_PROMPT
        )
        return WhisperAudioProcessor.sanitizeTranscript(raw)
    }

    @Synchronized
    fun release() {
        if (ctxPtr != 0L) {
            nativeFree(ctxPtr)
            ctxPtr = 0L
            loadedModelPath = null
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree(ctxPtr: Long)
    private external fun nativeTranscribe(
        ctxPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String,
        initialPrompt: String
    ): String

    companion object {
        private const val INDONESIAN_PROMPT =
            "Transkripsi Bahasa Indonesia sehari-hari, formal, santai, dan bahasa gaul."

        init {
            System.loadLibrary("wboard_whisper")
        }
    }
}
