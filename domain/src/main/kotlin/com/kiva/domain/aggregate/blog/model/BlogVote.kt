package com.kiva.domain.aggregate.blog.model

import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.common.text.normalizePersianLetters

/**
 * One answer to «این مطلب مفید بود؟» (roadmap phase 15, decision 14): [helpful], and with «no» a plain text [comment]
 * only the panel reads. A comment sent with «yes» is dropped, not refused.
 */
class BlogVote private constructor(val helpful: Boolean, val comment: String?) {

    override fun toString(): String = "BlogVote(helpful=$helpful, comment=${comment?.length})"

    companion object {
        const val MAX_COMMENT = 300

        /** `helpful` missing is FIELD_REQUIRED; a «no» comment over [MAX_COMMENT] or with a control character is refused. */
        fun makeNew(helpful: Boolean?, comment: String?): Result<BlogVote, InputProblems> {
            if (helpful == null) return Result.failure(InputProblems(listOf(FieldFailure("helpful", FieldRequired))))
            if (helpful) return Result.success(BlogVote(true, null))
            val text = comment?.replace("\r\n", "\n")?.lines()?.joinToString("\n") { it.normalizePersianLetters() }?.trim()?.ifEmpty { null }
                ?: return Result.success(BlogVote(false, null))
            return when {
                text.any { it.isISOControl() && it != '\n' } -> Result.failure(InputProblems(listOf(FieldFailure("comment", TextInvalid))))
                text.length > MAX_COMMENT -> Result.failure(InputProblems(listOf(FieldFailure("comment", TextTooLong(MAX_COMMENT)))))
                else -> Result.success(BlogVote(false, text))
            }
        }
    }
}
