package com.wboard.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperAudioProcessorTest {

    @Test
    fun trimSilence_returnsEmptyForQuietAudio() {
        val silence = FloatArray(16000) { 0.001f }
        val trimmed = WhisperAudioProcessor.trimSilence(silence)
        assertEquals(0, trimmed.size)
    }

    @Test
    fun trimSilence_keepsSpeechWithPadding() {
        val samples = FloatArray(32000) { 0f }
        // 0.5s speech burst around the middle
        for (i in 12000 until 20000) {
            samples[i] = 0.25f
        }
        val trimmed = WhisperAudioProcessor.trimSilence(samples)
        assertTrue(trimmed.size in 12000..17000)
    }

    @Test
    fun sanitizeTranscript_removesWhisperBracketArtifacts() {
        val raw = "  [BLANK_AUDIO] Halo apa kabar (musik) *tertawa* semua  "
        assertEquals("Halo apa kabar semua", WhisperAudioProcessor.sanitizeTranscript(raw))
    }
}
