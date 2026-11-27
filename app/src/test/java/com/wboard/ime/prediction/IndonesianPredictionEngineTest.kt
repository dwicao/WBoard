package com.wboard.ime.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class IndonesianPredictionEngineTest {

    private lateinit var engine: IndonesianPredictionEngine

    @Before
    fun setUp() {
        val unigrams = """
            yang	9800
            selamat	5000
            pagi	4800
            malam	4500
            tidak	8700
            terima	4900
            kasih	4880
            saya	8600
            selalu	4200
            selesai	2700
        """.trimIndent().byteInputStream()

        val bigrams = """
            selamat	pagi	950
            selamat	malam	920
            terima	kasih	990
        """.trimIndent().byteInputStream()

        val shorthands = """
            yg	yang
            tdk	tidak
        """.trimIndent().byteInputStream()

        engine = IndonesianPredictionEngine().apply {
            loadFromStreams(unigrams, bigrams, shorthands)
        }
    }

    @Test
    fun prefixCompletion_ordersByFrequencyAndPreservesCase() {
        val res = engine.suggest("Sel", previousWord = null)
        assertEquals(3, res.suggestions.size)
        assertEquals("Selamat", res.suggestions[0].text)
        assertEquals("Selalu", res.suggestions[1].text)
        assertEquals("Selesai", res.suggestions[2].text)
        assertNull(res.autoCommitCandidate)
    }

    @Test
    fun nextWordBigram_predictsIndonesianFollowUp() {
        val res = engine.suggest("", previousWord = "selamat")
        assertTrue(res.suggestions.isNotEmpty())
        assertEquals("pagi", res.suggestions[0].text)
        assertEquals("malam", res.suggestions[1].text)
    }

    @Test
    fun shorthandExpansion_suggestsFullWordAndKeepsVerbatim() {
        val res = engine.suggest("yg", previousWord = null, enableShorthand = true, enableAutocorrect = true)
        assertEquals("yang", res.suggestions[0].text)
        assertEquals("yg", res.suggestions[1].text)
        assertEquals("yang", res.autoCommitCandidate)
    }

    @Test
    fun fuzzyTypoCorrection_fixesAdjacentAndTransposedTypos() {
        val res = engine.suggest("selmat", previousWord = null, enableAutocorrect = true)
        assertNotNull(res.autoCommitCandidate)
        assertEquals("selamat", res.autoCommitCandidate)
        assertEquals("selamat", res.suggestions[0].text)
    }

    @Test
    fun allCapsInput_preservesUpperCase() {
        val res = engine.suggest("TER", previousWord = null)
        assertEquals("TERIMA", res.suggestions[0].text)
    }
}
