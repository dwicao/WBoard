package com.wboard.ime.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Kotlin audio & transcript helper for Whisper offline dictation:
 * - Normalized RMS calculation for live waveform animation
 * - Energy-based silence trimming (prevents Whisper silence hallucinations & speeds up decoding)
 * - Hallucination / non-speech tag cleanup
 */
object WhisperAudioProcessor {

    const val SAMPLE_RATE_HZ = 16000
    private const val MIN_SPEECH_SAMPLES = SAMPLE_RATE_HZ / 4 // 250ms
    private const val PAD_SAMPLES = SAMPLE_RATE_HZ / 4        // 250ms padding around speech

    fun computeNormalizedRms(pcmChunk: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val f = pcmChunk[i] / 32768.0
            sumSq += f * f
        }
        val rms = sqrt(sumSq / count).toFloat()
        // Boost typical speech RMS [0.005 .. 0.25] into [0 .. 1]
        return ((rms - 0.004f) / 0.18f).coerceIn(0f, 1f)
    }

    fun trimSilence(
        samples: FloatArray,
        threshold: Float = 0.012f
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)

        val window = SAMPLE_RATE_HZ / 50 // 20ms frames (320 samples)
        var firstSpeech = -1
        var lastSpeech = -1

        var i = 0
        while (i < samples.size) {
            val end = min(samples.size, i + window)
            var sumSq = 0.0
            for (j in i until end) {
                val s = samples[j].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / (end - i)).toFloat()
            if (rms >= threshold) {
                if (firstSpeech == -1) firstSpeech = i
                lastSpeech = end
            }
            i = end
        }

        if (firstSpeech == -1 || lastSpeech - firstSpeech < MIN_SPEECH_SAMPLES) {
            return FloatArray(0)
        }

        val start = max(0, firstSpeech - PAD_SAMPLES)
        val stop = min(samples.size, lastSpeech + PAD_SAMPLES)
        return samples.copyOfRange(start, stop)
    }

    fun sanitizeTranscript(raw: String): String {
        val cleaned = raw
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\*[^*]*\*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned
    }
}
