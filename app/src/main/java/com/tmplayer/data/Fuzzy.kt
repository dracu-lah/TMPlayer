package com.tmplayer.data

import java.text.Normalizer

/**
 * Forgiving text matching for the two search boxes.
 *
 * Both are typed into badly: a remote's on-screen keyboard makes every letter a journey, and a
 * phone's keyboard autocorrects a series name into an English word. A plain `contains` answers
 * "nothing found" to both.
 *
 * So: accents are folded away, punctuation stops counting, a query is a set of words rather than a
 * string, and a candidate is a match when enough of those words land. One wrong word among right
 * ones is forgiven, and a word that is nearly right still scores.
 *
 * Pure and free of Android, so the whole of it is exercised by plain JVM tests.
 */
object Fuzzy {

    /**
     * Lower case, no accents, and nothing but letters, digits and single spaces.
     *
     * The accent folding is what lets "Pokemon" find "Pokémon".
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (character in decomposed) {
            when {
                // The accents themselves, now sitting as separate combining marks after NFD.
                character.isCombiningMark() -> Unit
                character.isLetterOrDigit() -> builder.append(character.lowercaseChar())
                else -> builder.append(' ')
            }
        }
        return builder.toString().trim().replace(SPACES, " ")
    }

    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }

    /**
     * How well [candidate] answers [query], or zero when it does not.
     *
     * The scale is deliberately coarse: an exact word beats a prefix beats a substring beats a
     * near miss. Fine-grained scoring would only shuffle results that are all equally right.
     */
    fun score(candidate: String, query: String): Int {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return 1
        val normalizedCandidate = normalize(candidate)
        if (normalizedCandidate.isEmpty()) return 0
        val candidateTokens = normalizedCandidate.split(' ').filter { it.isNotEmpty() }

        var total = 0
        var matched = 0
        for (token in queryTokens) {
            val best = candidateTokens.maxOfOrNull { tokenScore(it, token) } ?: 0
            if (best > 0) matched++
            total += best
        }

        // Half the words, rounded up. Typing five words and getting one wrong should still find
        // the thing; typing five and getting four wrong is a different search.
        val required = (queryTokens.size + 1) / 2
        if (matched < required) return 0

        // The whole query as one run of characters, which is what an exact search feels like.
        // Worth a large bonus so a true match sorts above everything that merely shares words.
        if (normalizedCandidate.contains(normalize(query))) total += WHOLE_QUERY_BONUS
        return total
    }

    /** [items] that answer [query], best first and otherwise in the order they arrived. */
    fun <T> rank(items: List<T>, query: String, key: (T) -> String): List<T> {
        if (query.isBlank()) return items
        return items
            .map { it to score(key(it), query) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun tokenScore(candidateToken: String, queryToken: String): Int = when {
        candidateToken == queryToken -> EXACT
        candidateToken.startsWith(queryToken) -> PREFIX
        candidateToken.contains(queryToken) -> SUBSTRING
        // Only for words long enough that one wrong letter is a typo rather than a different
        // word: at three characters "the" and "she" are one edit apart and mean nothing alike.
        queryToken.length >= MIN_TYPO_LENGTH &&
            candidateToken.length >= MIN_TYPO_LENGTH &&
            nearlyEqual(candidateToken, queryToken) -> TYPO
        else -> 0
    }

    /**
     * Whether [a] and [b] are one typo apart.
     *
     * Two shapes count: one insertion, deletion or substitution, or a swapped pair of neighbouring
     * letters. The swap needs naming separately because it is two edits by the usual arithmetic
     * while being the commonest typing mistake there is ("freinds" for "friends").
     */
    internal fun nearlyEqual(a: String, b: String): Boolean =
        withinOneEdit(a, b) || oneTransposition(a, b)

    /** Same letters, with exactly one adjacent pair swapped. */
    private fun oneTransposition(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val differing = a.indices.filter { a[it] != b[it] }
        if (differing.size != 2) return false
        val (first, second) = differing
        return second == first + 1 && a[first] == b[second] && a[second] == b[first]
    }

    /**
     * Whether one insertion, deletion or substitution turns [a] into [b].
     *
     * Anything two or more edits away is a different word, so the walk stops at the first mismatch
     * it cannot absorb rather than building a full edit-distance matrix.
     */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (longer.length - shorter.length > 1) return false

        var shortIndex = 0
        var longIndex = 0
        var edited = false
        while (shortIndex < shorter.length && longIndex < longer.length) {
            if (shorter[shortIndex] == longer[longIndex]) {
                shortIndex++
                longIndex++
                continue
            }
            if (edited) return false
            edited = true
            // Same length means the differing character was substituted, so both sides move on.
            // Different lengths mean the longer one has a character the shorter does not.
            if (shorter.length == longer.length) shortIndex++
            longIndex++
        }
        return true
    }

    /** True for the combining marks NFD leaves behind once an accented letter is decomposed. */
    private fun Char.isCombiningMark(): Boolean = when (Character.getType(this).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        -> true
        else -> false
    }

    private val SPACES = Regex(" +")

    private const val EXACT = 4
    private const val PREFIX = 3
    private const val SUBSTRING = 2
    private const val TYPO = 2
    private const val MIN_TYPO_LENGTH = 4
    private const val WHOLE_QUERY_BONUS = 8
}
