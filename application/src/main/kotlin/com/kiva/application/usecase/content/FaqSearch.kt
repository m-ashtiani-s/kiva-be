package com.kiva.application.usecase.content

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.MatchRanges
import com.kiva.common.text.normalizePersian

/**
 * What `GET /faq?q=` looks for (roadmap phase 5, decision 17): the query, normalized, matched letter by letter in a
 * question and in an answer's text, without minding Arabic or Persian `ی`/`ک`, Persian or Latin digits, or capitals.
 * The matching and marking are MatchRanges', the same as the live search's (phase 12): folding never changes a text's
 * length, so `<mark>` goes exactly around the original letters.
 */
class FaqSearch private constructor(private val folded: String) {

    /** [text] contains the query. */
    fun matches(text: String): Boolean = fold(text).contains(folded)

    /** Where the query is in [text], left to right, overlapping ones merged. */
    fun rangesIn(text: String): List<IntRange> = MatchRanges.of(text, listOf(folded))

    /**
     * [text], plain text such as a question, made safe to put into a page as HTML (every `<`, `>`, `&` and quote escaped),
     * with `<mark>` around each match: an editor's `<b>` in a question shows as text, never as a tag.
     */
    fun markText(text: String): String = MatchRanges.mark(text, rangesIn(text))

    companion object {
        const val MAX_QUERY = 100

        /** The query as `q` came: null (no search) when it is missing or empty after normalizing; over [MAX_QUERY], 400 on `q`. */
        fun of(raw: String?): Result<FaqSearch?, ResultFailure> {
            val query = raw?.normalizePersian()?.ifEmpty { null } ?: return Result.success(null)
            if (query.length > MAX_QUERY) return Result.failure<ResultFailure>(TextTooLong(MAX_QUERY)).forField("q")
            return Result.success(FaqSearch(fold(query)))
        }

        /** MatchRanges.fold: the same length, Arabic letters as Persian ones, every digit Latin, Latin letters small. */
        fun fold(text: String): String = MatchRanges.fold(text)
    }
}
