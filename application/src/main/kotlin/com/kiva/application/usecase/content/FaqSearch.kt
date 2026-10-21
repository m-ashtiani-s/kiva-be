package com.kiva.application.usecase.content

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersian

/**
 * What `GET /faq?q=` looks for (roadmap phase 5, decision 17): the query, normalized, matched letter by letter in a
 * question and in an answer's text, without minding Arabic or Persian `ی`/`ک`, Persian or Latin digits, or capitals.
 * Folding never changes a text's length, so a match's place in the folded text is its place in the text itself, and
 * `<mark>` goes exactly around it.
 */
class FaqSearch private constructor(private val folded: String) {

    /** [text] contains the query. */
    fun matches(text: String): Boolean = fold(text).contains(folded)

    /** Where the query is in [text], left to right, never overlapping. */
    fun rangesIn(text: String): List<IntRange> {
        val haystack = fold(text)
        val ranges = mutableListOf<IntRange>()
        var from = haystack.indexOf(folded)
        while (from >= 0) {
            ranges += from until from + folded.length
            from = haystack.indexOf(folded, from + folded.length)
        }
        return ranges
    }

    /**
     * [text], plain text such as a question, made safe to put into a page as HTML (every `<`, `>`, `&` and quote escaped),
     * with `<mark>` around each match: an editor's `<b>` in a question shows as text, never as a tag.
     */
    fun markText(text: String): String = buildString {
        var at = 0
        rangesIn(text).forEach { range ->
            append(escape(text.substring(at, range.first)))
            append("<mark>").append(escape(text.substring(range.first, range.last + 1))).append("</mark>")
            at = range.last + 1
        }
        append(escape(text.substring(at)))
    }

    companion object {
        const val MAX_QUERY = 100

        /** The query as `q` came: null (no search) when it is missing or empty after normalizing; over [MAX_QUERY], 400 on `q`. */
        fun of(raw: String?): Result<FaqSearch?, ResultFailure> {
            val query = raw?.normalizePersian()?.ifEmpty { null } ?: return Result.success(null)
            if (query.length > MAX_QUERY) return Result.failure<ResultFailure>(TextTooLong(MAX_QUERY)).forField("q")
            return Result.success(FaqSearch(fold(query)))
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
}
