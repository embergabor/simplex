package com.ember.simpleplex.ui

import java.text.Normalizer

/**
 * Matching for search: every word of the query must appear, ignoring case and accents. A word also
 * matches across spaces and punctuation, so "topgear" finds "Top Gear".
 */
object SearchMatch {
    private val marks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")

    fun terms(query: String): List<String> = normalize(query).split(separators).filter { it.isNotEmpty() }

    /** Searchable text, prepared once so matching while typing stays fast. */
    class Text(raw: String) {
        private val plain = normalize(raw)
        private val compact = plain.replace(separators, "")
        fun has(term: String) = term in plain || term in compact
    }

    /**
     * True when every term is found in [own] or [context] (an episode's show title). At least one term
     * must be in [own], so a show's name alone doesn't list all of its episodes.
     */
    fun matches(terms: List<String>, own: Text, context: Text? = null): Boolean =
        terms.isNotEmpty() &&
            terms.all { own.has(it) || context?.has(it) == true } &&
            (context == null || terms.any { own.has(it) })
}
