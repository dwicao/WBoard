package com.wboard.ime.voice

/**
 * Pure Kotlin post-processor for Indonesian voice dictation output.
 * Converts spoken Indonesian punctuation commands and normalizes spacing/capitalization.
 */
object IndonesianVoicePostProcessor {

    private val MULTI_WORD_PUNCTUATION = listOf(
        "tanda tanya" to "?",
        "tanda seru" to "!",
        "titik dua" to ":",
        "titik koma" to ";",
        "baris baru" to "\n",
        "paragraf baru" to "\n\n"
    )

    private val SINGLE_WORD_PUNCTUATION = mapOf(
        "titik" to ".",
        "koma" to ","
    )

    fun process(
        rawText: String,
        applyPunctuationCommands: Boolean,
        capitalizeFirst: Boolean = false
    ): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        var text = trimmed
        if (applyPunctuationCommands) {
            for ((phrase, symbol) in MULTI_WORD_PUNCTUATION) {
                val regex = Regex("""(?i)\b${Regex.escape(phrase)}\b""")
                text = regex.replace(text) { " $symbol " }
            }
            for ((word, symbol) in SINGLE_WORD_PUNCTUATION) {
                val regex = Regex("""(?i)\b${Regex.escape(word)}\b""")
                text = regex.replace(text) { " $symbol " }
            }
            // Remove spaces before punctuation symbols and collapse extra spaces
            text = text
                .replace(Regex("""[ \t]+([.,?!:;])"""), "$1")
                .replace(Regex("""([.,?!:;])([^\s\n.,?!:;])"""), "$1 $2")
                .replace(Regex("""[ \t]*\n[ \t]*"""), "\n")
                .replace(Regex("""[ \t]{2,}"""), " ")
                .trim()
        }

        // Capitalize letters following sentence terminators or newlines
        text = capitalizeAfterSentenceEnds(text, capitalizeFirst)
        return text
    }

    private fun capitalizeAfterSentenceEnds(input: String, capitalizeStart: Boolean): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        var capNext = capitalizeStart
        for (ch in input) {
            val outChar = if (capNext && ch.isLetter()) {
                capNext = false
                ch.uppercaseChar()
            } else {
                if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') {
                    capNext = true
                } else if (!ch.isWhitespace()) {
                    capNext = false
                }
                ch
            }
            sb.append(outChar)
        }
        return sb.toString()
    }
}
