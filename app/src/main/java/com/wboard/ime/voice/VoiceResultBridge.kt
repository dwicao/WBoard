package com.wboard.ime.voice

/**
 * In-process handoff for Indonesian voice results captured via VoiceInputActivity
 * when the IME service window is temporarily hidden during speech dialog fallback.
 */
object VoiceResultBridge {
    @Volatile
    private var pendingText: String? = null

    fun postPending(text: String) {
        if (text.isNotBlank()) {
            pendingText = text
        }
    }

    fun consumePending(): String? {
        val text = pendingText
        pendingText = null
        return text
    }
}
