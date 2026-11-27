package com.wboard.ime.prediction

import android.content.Context
import java.io.File

/**
 * Stores opt-in locally learned words in private app-internal storage (never backed up,
 * hard-capped at MAX_ENTRIES, and wipeable in one tap).
 */
class UserLexiconStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(): MutableMap<String, Int> {
        val map = LinkedHashMap<String, Int>()
        if (!file.exists()) return map
        runCatching {
            file.forEachLine(Charsets.UTF_8) { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val word = line.substring(0, tab).trim().lowercase()
                    val count = line.substring(tab + 1).trim().toIntOrNull() ?: 0
                    if (word.length >= 2 && count > 0) {
                        map[word] = count
                    }
                }
            }
        }
        return map
    }

    fun recordWord(word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2 || clean.length > 36) return
        if (!clean.all { it.isLetter() || it == '-' }) return

        val current = load()
        current[clean] = (current[clean] ?: 0) + 1
        val pruned = if (current.size > MAX_ENTRIES) {
            current.entries
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        } else {
            current
        }
        save(pruned)
    }

    fun clear(): Int {
        val count = load().size
        if (file.exists()) {
            file.delete()
        }
        return count
    }

    private fun save(map: Map<String, Int>) {
        runCatching {
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                for ((word, count) in map) {
                    writer.write("$word\t$count\n")
                }
            }
        }
    }

    companion object {
        private const val FILE_NAME = "user_lexicon.tsv"
        private const val MAX_ENTRIES = 500
    }
}
