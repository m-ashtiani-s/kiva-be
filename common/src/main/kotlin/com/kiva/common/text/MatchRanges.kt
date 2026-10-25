package com.kiva.common.text

/**
 * Where search words are in a text as it is stored, and the text marked there (roadmap phase 12, decision 6; the FAQ's
 * search of phase 5 too): matching is done on a folded copy, letter by letter, so a match's place in the copy is its
 * place in the original and `<mark>` goes around the original letters (a ZWNJ, a `ي` or a Persian digit stays as it was
 * written). Pure functions.
 */
object MatchRanges {

    /** Each place of each of [terms] (already folded) in [text], overlapping ones merged into one, left to right. */
    fun of(text: String, terms: Collection<String>): List<IntRange> {
        val haystack = fold(text)
        val found = terms.filter { it.isNotEmpty() }.flatMap { term ->
            buildList {
                var from = haystack.indexOf(term)
                while (from >= 0) {
                    add(from until from + term.length)
                    from = haystack.indexOf(term, from + 1)
                }
            }
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in found) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) merged[merged.size - 1] = last.first..maxOf(last.last, range.last) else merged += range
        }
        return merged
    }

    /**
     * [text], plain text, made safe for a page as HTML (every `<`, `>`, `&` and quote escaped), with `<mark>` around each
     * of [ranges]: nothing else of [text] can ever be a tag.
     */
    fun mark(text: String, ranges: List<IntRange>): String = buildString {
        var at = 0
        ranges.forEach { range ->
            append(escape(text.substring(at, range.first)))
            append("<mark>").append(escape(text.substring(range.first, range.last + 1))).append("</mark>")
            at = range.last + 1
        }
        append(escape(text.substring(at)))
    }

    /** Letter by letter: the same length, Arabic `ي ى ك` as Persian `ی ک`, every digit Latin, Latin letters small. */
    fun fold(text: String): String = buildString(text.length) {
        for (char in text) {
            append(
                when (char) {
                    'ي', 'ى' -> 'ی'
                    'ك' -> 'ک'
                    in '۰'..'۹' -> '0' + (char - '۰')
                    in '٠'..'٩' -> '0' + (char - '٠')
                    else -> char.lowercaseChar()
                },
            )
        }
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
