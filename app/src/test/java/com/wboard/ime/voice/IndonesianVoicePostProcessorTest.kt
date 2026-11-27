package com.wboard.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class IndonesianVoicePostProcessorTest {

    @Test
    fun convertsIndonesianPunctuationCommands() {
        val out = IndonesianVoicePostProcessor.process(
            rawText = "halo koma apa kabar tanda tanya baik titik terima kasih tanda seru",
            applyPunctuationCommands = true,
            capitalizeFirst = true
        )
        assertEquals("Halo, apa kabar? Baik. Terima kasih!", out)
    }

    @Test
    fun handlesNewlineCommand() {
        val out = IndonesianVoicePostProcessor.process(
            rawText = "baris pertama baris baru baris kedua",
            applyPunctuationCommands = true,
            capitalizeFirst = true
        )
        assertEquals("Baris pertama\nBaris kedua", out)
    }

    @Test
    fun leavesWordsUntouchedWhenPunctuationCommandsDisabled() {
        val out = IndonesianVoicePostProcessor.process(
            rawText = "titik koma",
            applyPunctuationCommands = false,
            capitalizeFirst = false
        )
        assertEquals("titik koma", out)
    }
}
