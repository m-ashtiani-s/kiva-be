package com.kiva.domain.aggregate.product.model.policy

import com.kiva.common.text.normalizePersian

/**
 * How a shopper's search text is read (roadmap phase 12, decisions 1, 2 and 5), the same for the live search and for
 * `GET /products?q=`: normalized (normalizePersian, then a ZWNJ is a space, runs of spaces one), split into words, at
 * most [MAX_WORDS] of them; a product must have every word somewhere in its search text.
 */
object SearchQueryPolicy {
    const val MAX_WORDS = 6
    const val MAX_LENGTH = 100

    private const val ZWNJ = '‌'
    private val SPACES = Regex("\\s+")

    /** [raw] as the search reads it; empty when nothing but spaces and half-spaces came. */
    fun normalize(raw: String): String = raw.normalizePersian().replace(ZWNJ, ' ').replace(SPACES, " ").trim()

    /** The words of a [normalize]d query, small letters, at most [MAX_WORDS]. */
    fun wordsOf(normalized: String): List<String> = normalized.lowercase().split(' ').filter { it.isNotEmpty() }.take(MAX_WORDS)

    /**
     * The colour the query names (decision 5): the longest of [palette] (key to name) whose normalized name is in the
     * normalized query as whole words («مشکی» in «کراس مشکی», «سبز زیتونی» in «کیف سبز زیتونی»); none: null.
     */
    fun <K> matchedColorOf(normalized: String, palette: Map<K, String>): K? {
        val padded = " ${normalized.lowercase()} "
        return palette.entries
            .map { (key, name) -> key to normalize(name).lowercase() }
            .filter { (_, name) -> name.isNotEmpty() && padded.contains(" $name ") }
            .maxByOrNull { (_, name) -> name.length }
            ?.first
    }
}
