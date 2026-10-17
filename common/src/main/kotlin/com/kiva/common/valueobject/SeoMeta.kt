package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.zip
import com.kiva.common.text.normalizePersianLetters

/**
 * What a page tells search engines beyond its own name and text: the contract's SeoMeta as a panel form sends it
 * (`seo.title`, `seo.description`, `seo.noIndex`). Products, categories, static pages and blog posts all take the same
 * three fields with the same limits; `canonicalUrl`, `ogImageUrl` and `jsonLd` are built by the server, never typed.
 *
 * Texts are trimmed (Persian letters normalized, as they are shown); empty is null, so "no title of its own".
 *
 * @property title null to use the page's own name.
 * @property description null to use the page's own summary.
 * @property noIndex true asks search engines to leave the page out.
 */
@ConsistentCopyVisibility
data class SeoMeta private constructor(val title: String?, val description: String?, val noIndex: Boolean) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(title == null || (title.isNotBlank() && title.length <= MAX_TITLE)) { "Invalid SeoMeta title '$title'" }
        require(description == null || (description.isNotBlank() && description.length <= MAX_DESCRIPTION)) { "Invalid SeoMeta description" }
    }

    companion object {
        const val MAX_TITLE = 70
        const val MAX_DESCRIPTION = 170

        /** Nothing of its own: the page's name and summary are used, and search engines may index it. */
        val NONE = SeoMeta(null, null, false)

        /** The three fields as the form sent them; a missing `seo` object is [NONE]. Every problem at once, under `seo.…`. */
        fun makeNew(title: String?, description: String?, noIndex: Boolean?): Result<SeoMeta, ResultFailure> {
            val cleanTitle = title?.normalizePersianLetters()?.ifEmpty { null }
            val cleanDescription = description?.normalizePersianLetters()?.ifEmpty { null }
            return Result.zip(
                if ((cleanTitle?.length ?: 0) > MAX_TITLE) Result.failure(Failure.TitleTooLong) else Result.success(cleanTitle),
                if ((cleanDescription?.length ?: 0) > MAX_DESCRIPTION) Result.failure(Failure.DescriptionTooLong) else Result.success(cleanDescription),
                ::InputProblems,
            ) { checkedTitle, checkedDescription -> SeoMeta(checkedTitle, checkedDescription, noIndex ?: false) }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(title: String?, description: String?, noIndex: Boolean): SeoMeta = SeoMeta(title, description, noIndex)
    }

    sealed class Failure(code: String, field: String, meta: Map<String, Any>) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object TitleTooLong : Failure("SEO_TITLE_TOO_LONG", "seo.title", mapOf("maxLength" to MAX_TITLE))
        data object DescriptionTooLong : Failure("SEO_DESCRIPTION_TOO_LONG", "seo.description", mapOf("maxLength" to MAX_DESCRIPTION))
    }
}
