package com.wboard.ime.prediction

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure Kotlin, 100% on-device Indonesian text prediction and typo-correction engine.
 * Supports:
 * - Trie-based prefix completion weighted by Indonesian word frequency
 * - Next-word bigram prediction
 * - Indonesian shorthand expansion (e.g., yg -> yang, tdk -> tidak)
 * - QWERTY-proximity weighted Damerau-Levenshtein autocorrect
 * - Optional local learned-word boosting
 */
class IndonesianPredictionEngine {

    data class WordEntry(val word: String, val freq: Int)

    data class Suggestion(
        val text: String,
        val isCorrection: Boolean = false,
        val isVerbatim: Boolean = false
    )

    data class PredictionResult(
        val suggestions: List<Suggestion>,
        val autoCommitCandidate: String?
    )

    private class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var word: String? = null
        var freq: Int = 0
    }

    private val root = TrieNode()
    private val lexicon = HashMap<String, Int>(4096)
    private val wordsByLength = HashMap<Int, MutableList<WordEntry>>(24)
    private val bigrams = HashMap<String, List<WordEntry>>(512)
    private val shorthands = HashMap<String, String>(128)
    private val userWords = HashMap<String, Int>(512)

    @Volatile
    var isLoaded: Boolean = false
        private set

    fun loadFromStreams(
        unigramStream: InputStream,
        bigramStream: InputStream?,
        shorthandStream: InputStream?
    ) {
        clearBase()
        BufferedReader(InputStreamReader(unigramStream, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val word = line.substring(0, tab).trim().lowercase()
                val freq = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
                if (word.isNotEmpty() && freq > 0) {
                    insertWord(word, freq)
                }
            }
        }

        if (bigramStream != null) {
            val grouped = HashMap<String, MutableList<WordEntry>>(512)
            BufferedReader(InputStreamReader(bigramStream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEachLine
                    val w1 = parts[0].trim().lowercase()
                    val w2 = parts[1].trim().lowercase()
                    val freq = parts[2].trim().toIntOrNull() ?: return@forEachLine
                    if (w1.isNotEmpty() && w2.isNotEmpty() && freq > 0) {
                        grouped.getOrPut(w1) { ArrayList(6) }.add(WordEntry(w2, freq))
                    }
                }
            }
            for ((k, list) in grouped) {
                bigrams[k] = list.sortedByDescending { it.freq }.take(6)
            }
        }

        if (shorthandStream != null) {
            BufferedReader(InputStreamReader(shorthandStream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val shortForm = line.substring(0, tab).trim().lowercase()
                    val fullForm = line.substring(tab + 1).trim().lowercase()
                    if (shortForm.isNotEmpty() && fullForm.isNotEmpty()) {
                        shorthands[shortForm] = fullForm
                    }
                }
            }
        }

        isLoaded = true
    }

    fun setUserWords(words: Map<String, Int>) {
        synchronized(this) {
            userWords.clear()
            for ((rawWord, count) in words) {
                val w = rawWord.trim().lowercase()
                if (w.length >= 2 && count > 0) {
                    userWords[w] = count
                    if (!lexicon.containsKey(w)) {
                        insertWord(w, BASE_USER_FREQ + count * 40)
                    }
                }
            }
        }
    }

    fun recordUserWord(word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2 || !clean.all { it.isLetter() || it == '-' }) return
        synchronized(this) {
            val nextCount = (userWords[clean] ?: 0) + 1
            userWords[clean] = nextCount
            val boosted = (lexicon[clean] ?: BASE_USER_FREQ) + 50
            insertWord(clean, boosted)
        }
    }

    fun clearUserWords() {
        synchronized(this) {
            for (w in userWords.keys) {
                // Only remove words that weren't part of core dictionary
                if ((lexicon[w] ?: 0) < 1000) {
                    // Leave trie intact until reload or keep lightweight; zero out user boost
                    userWords[w] = 0
                }
            }
            userWords.clear()
        }
    }

    fun suggest(
        composing: String,
        previousWord: String?,
        maxResults: Int = 3,
        enableShorthand: Boolean = true,
        enableAutocorrect: Boolean = true
    ): PredictionResult {
        if (maxResults <= 0) return PredictionResult(emptyList(), null)

        val trimmed = composing.trim()
        if (trimmed.isEmpty()) {
            return suggestNextWord(previousWord, maxResults)
        }

        val lower = trimmed.lowercase()
        val suggestions = ArrayList<Suggestion>(maxResults)
        val seen = HashSet<String>(maxResults * 2)

        // 1. Shorthand expansion check (e.g., yg -> yang)
        val shorthandTarget = if (enableShorthand) shorthands[lower] else null
        if (shorthandTarget != null) {
            val cased = applyCase(shorthandTarget, trimmed)
            suggestions.add(Suggestion(cased, isCorrection = enableAutocorrect))
            seen.add(cased.lowercase())
            if (suggestions.size < maxResults && seen.add(lower)) {
                suggestions.add(Suggestion(trimmed, isVerbatim = true))
            }
        }

        // 2. Prefix completions from Trie
        val exactKnown = lexicon.containsKey(lower) || userWords.containsKey(lower)
        val prefixCandidates = collectPrefix(lower, limit = 16)
            .sortedWith(
                compareByDescending<WordEntry> { it.word == lower }
                    .thenByDescending { effectiveFreq(it.word, it.freq) }
            )

        for (entry in prefixCandidates) {
            if (suggestions.size >= maxResults) break
            val cased = applyCase(entry.word, trimmed)
            if (seen.add(cased.lowercase())) {
                suggestions.add(Suggestion(cased, isCorrection = false))
            }
        }

        // 3. Fuzzy typo correction if no exact word match
        var fuzzyBest: String? = null
        if (!exactKnown && shorthandTarget == null && lower.length >= 2) {
            val fuzzyMatches = findFuzzyMatches(lower, limit = maxResults)
            if (fuzzyMatches.isNotEmpty()) {
                val topFuzzy = applyCase(fuzzyMatches.first().first, trimmed)
                if (enableAutocorrect &&
                    prefixCandidates.isEmpty() &&
                    fuzzyMatches.first().second <= AUTO_COMMIT_MAX_DIST
                ) {
                    fuzzyBest = topFuzzy
                }
                // Place best correction first if no prefix matches or if confident
                if (prefixCandidates.isEmpty()) {
                    for ((word, _) in fuzzyMatches) {
                        if (suggestions.size >= maxResults) break
                        val cased = applyCase(word, trimmed)
                        if (seen.add(cased.lowercase())) {
                            suggestions.add(Suggestion(cased, isCorrection = true))
                        }
                    }
                } else if (suggestions.size < maxResults && seen.add(topFuzzy.lowercase())) {
                    suggestions.add(Suggestion(topFuzzy, isCorrection = true))
                }
            }
        }

        val autoCommit = when {
            !enableAutocorrect -> null
            shorthandTarget != null -> applyCase(shorthandTarget, trimmed)
            fuzzyBest != null -> fuzzyBest
            else -> null
        }

        // Ensure verbatim option is visible when autocorrect would rewrite what the user typed
        if (autoCommit != null &&
            !exactKnown &&
            suggestions.none { it.text.equals(trimmed, ignoreCase = true) }
        ) {
            if (suggestions.size < maxResults) {
                suggestions.add(Suggestion(trimmed, isVerbatim = true))
            } else if (suggestions.size >= 2) {
                suggestions[suggestions.lastIndex] = Suggestion(trimmed, isVerbatim = true)
            }
        }

        return PredictionResult(suggestions.take(maxResults), autoCommit)
    }

    private fun suggestNextWord(previousWord: String?, maxResults: Int): PredictionResult {
        val prev = previousWord?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val result = ArrayList<Suggestion>(maxResults)
        val seen = HashSet<String>(maxResults * 2)

        if (prev != null) {
            val followers = bigrams[prev]
            if (followers != null) {
                for (entry in followers) {
                    if (result.size >= maxResults) break
                    if (seen.add(entry.word)) {
                        result.add(Suggestion(entry.word))
                    }
                }
            }
        }

        val fallbacks = if (prev == null) DEFAULT_STARTERS else DEFAULT_CONNECTORS
        for (word in fallbacks) {
            if (result.size >= maxResults) break
            if (seen.add(word)) {
                result.add(Suggestion(word))
            }
        }

        return PredictionResult(result, autoCommitCandidate = null)
    }

    private fun clearBase() {
        root.children.clear()
        root.word = null
        root.freq = 0
        lexicon.clear()
        wordsByLength.clear()
        bigrams.clear()
        shorthands.clear()
    }

    private fun insertWord(word: String, freq: Int) {
        val prevFreq = lexicon[word]
        if (prevFreq != null && prevFreq >= freq) return
        lexicon[word] = freq

        var node = root
        for (ch in word) {
            node = node.children.getOrPut(ch) { TrieNode() }
        }
        node.word = word
        node.freq = freq

        if (prevFreq == null) {
            wordsByLength.getOrPut(word.length) { ArrayList(64) }.add(WordEntry(word, freq))
        }
    }

    private fun effectiveFreq(word: String, baseFreq: Int): Int {
        val userBoost = (userWords[word] ?: 0) * 180
        return baseFreq + userBoost
    }

    private fun collectPrefix(prefix: String, limit: Int): List<WordEntry> {
        var node: TrieNode? = root
        for (ch in prefix) {
            node = node?.children?.get(ch) ?: return emptyList()
        }
        val out = ArrayList<WordEntry>(limit)
        val stack = ArrayDeque<TrieNode>()
        stack.addLast(node!!)
        while (stack.isNotEmpty() && out.size < limit * 4) {
            val cur = stack.removeLast()
            val w = cur.word
            if (w != null) {
                out.add(WordEntry(w, effectiveFreq(w, cur.freq)))
            }
            for (child in cur.children.values) {
                stack.addLast(child)
            }
        }
        return out
    }

    private fun findFuzzyMatches(query: String, limit: Int): List<Pair<String, Double>> {
        val len = query.length
        val maxWeightedDist = if (len <= 3) 1.05 else 1.65
        val scored = ArrayList<Triple<String, Double, Double>>(8)

        for (targetLen in maxOf(2, len - 1)..(len + 1)) {
            val bucket = wordsByLength[targetLen] ?: continue
            for (entry in bucket) {
                if (abs(entry.word.length - len) > 1) continue
                // Fast pre-filter: share at least one character of first two chars
                if (len >= 3 && entry.word[0] != query[0] && entry.word[1] != query[1] && entry.word[0] != query[1]) {
                    continue
                }
                val dist = weightedDamerauLevenshtein(query, entry.word, maxWeightedDist)
                if (dist <= maxWeightedDist) {
                    val freqScore = effectiveFreq(entry.word, entry.freq).toDouble()
                    val rankScore = freqScore / (1.0 + dist * 4.5)
                    scored.add(Triple(entry.word, dist, rankScore))
                }
            }
        }

        return scored
            .sortedByDescending { it.third }
            .take(limit)
            .map { it.first to it.second }
    }

    companion object {
        private const val BASE_USER_FREQ = 650
        private const val AUTO_COMMIT_MAX_DIST = 1.15

        private val DEFAULT_STARTERS = listOf("saya", "selamat", "halo")
        private val DEFAULT_CONNECTORS = listOf("yang", "dan", "di")

        private val ADJACENT_KEYS: Map<Char, String> = mapOf(
            'q' to "wa",
            'w' to "qesa",
            'e' to "wrsd",
            'r' to "etdf",
            't' to "ryfg",
            'y' to "tugh",
            'u' to "yihj",
            'i' to "uojk",
            'o' to "ipkl",
            'p' to "ol",
            'a' to "qwsz",
            's' to "awedxz",
            'd' to "serfcx",
            'f' to "drtgvc",
            'g' to "ftyhbv",
            'h' to "gyujnb",
            'j' to "huikmn",
            'k' to "jiolm",
            'l' to "kop",
            'z' to "asx",
            'x' to "zsdc",
            'c' to "xdfv",
            'v' to "cfgb",
            'b' to "vghn",
            'n' to "bhjm",
            'm' to "njk"
        )

        internal fun weightedDamerauLevenshtein(a: String, b: String, limit: Double): Double {
            val n = a.length
            val m = b.length
            val dp = Array(n + 1) { DoubleArray(m + 1) }
            for (i in 0..n) dp[i][0] = i.toDouble()
            for (j in 0..m) dp[0][j] = j.toDouble()

            for (i in 1..n) {
                var rowMin = Double.MAX_VALUE
                for (j in 1..m) {
                    val subCost = when {
                        a[i - 1] == b[j - 1] -> 0.0
                        ADJACENT_KEYS[a[i - 1]]?.contains(b[j - 1]) == true -> 0.45
                        else -> 1.0
                    }
                    var best = min(
                        min(dp[i - 1][j] + 1.0, dp[i][j - 1] + 1.0),
                        dp[i - 1][j - 1] + subCost
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        best = min(best, dp[i - 2][j - 2] + 0.55)
                    }
                    dp[i][j] = best
                    if (best < rowMin) rowMin = best
                }
                if (rowMin > limit + 1.0) return limit + 1.0
            }
            return dp[n][m]
        }

        internal fun applyCase(candidate: String, typed: String): String {
            if (typed.isEmpty()) return candidate
            val letters = typed.filter { it.isLetter() }
            if (letters.isEmpty()) return candidate
            if (letters.length > 1 && letters.all { it.isUpperCase() }) {
                return candidate.uppercase()
            }
            if (typed.first().isUpperCase()) {
                return candidate.replaceFirstChar { it.uppercaseChar() }
            }
            return candidate
        }
    }
}
