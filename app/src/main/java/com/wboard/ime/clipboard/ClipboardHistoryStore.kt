package com.wboard.ime.clipboard

/**
 * Strictly in-memory (RAM-only) clipboard history for the current IME session.
 * Never persisted to disk and automatically skips sensitive clips (e.g. password managers).
 */
class ClipboardHistoryStore(private val maxItems: Int = DEFAULT_MAX_ITEMS) {

    private val entries = ArrayDeque<String>(maxItems)

    @Synchronized
    fun recordClip(rawText: String?, isSensitive: Boolean = false): Boolean {
        if (isSensitive) return false
        val text = rawText?.takeIf { it.isNotBlank() && it.length <= MAX_CLIP_CHARS } ?: return false
        entries.remove(text)
        entries.addFirst(text)
        while (entries.size > maxItems) {
            entries.removeLast()
        }
        return true
    }

    @Synchronized
    fun items(): List<String> = entries.toList()

    @Synchronized
    fun remove(text: String) {
        entries.remove(text)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 15
        const val MAX_CLIP_CHARS = 4096
    }
}
