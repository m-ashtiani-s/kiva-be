package com.kiva.domain.aggregate.page.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer

/**
 * A static page as the panel's form sends it (`PUT /admin/pages/{slug}`; roadmap phase 5, decision 7), checked: every
 * problem at once, each on its field.
 *
 * @property title trimmed, Persian letters normalized; 1 to [MAX_TITLE].
 * @property content the HTML after [HtmlSanitizer]; something left, at most [MAX_CONTENT].
 */
@ConsistentCopyVisibility
data class PageDraft private constructor(val title: String, val content: String, val seo: SeoMeta) {

    // Second wall: makeNew refuses these with a Failure, so only broken stored data gets here. A bug: stop loudly.
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "Invalid page title '$title'" }
        require(content.isNotBlank() && content.length <= MAX_CONTENT) { "Invalid page content of ${content.length}" }
    }

    override fun toString(): String = "PageDraft(title=$title, content=${content.length} chars)"

    companion object {
        const val MAX_TITLE = 120
        const val MAX_CONTENT = 50_000

        /** The form's fields as they came; [content] is cleaned before it is measured. */
        fun makeNew(
            title: String?,
            content: String?,
            seoTitle: String?,
            seoDescription: String?,
            seoNoIndex: Boolean?,
            sanitizer: HtmlSanitizer,
        ): Result<PageDraft, InputProblems> {
            val problems = Problems()
            val cleanTitle = problems.take(titleOf(title).forField("title"))
            val cleanContent = problems.take(contentOf(content, sanitizer).forField("content"))
            val seo = problems.take(SeoMeta.makeNew(seoTitle, seoDescription, seoNoIndex))
            // Flat: the search engine fields come grouped from SeoMeta, and a form shows each problem on its own field.
            if (problems.any()) return Result.failure(InputProblems(problems.all().causes.flattenGroups()))
            return Result.success(PageDraft(cleanTitle.value, cleanContent.value, seo.value))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(title: String, content: String, seo: SeoMeta): PageDraft = PageDraft(title, content, seo)

        private fun titleOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(Failure.TitleRequired)
            return if (text.length > MAX_TITLE) Result.failure(TextTooLong(MAX_TITLE)) else Result.success(text)
        }

        private fun contentOf(raw: String?, sanitizer: HtmlSanitizer): Result<String, ResultFailure> {
            val clean = raw?.let(sanitizer::clean)?.trim()?.ifEmpty { null } ?: return Result.failure(Failure.ContentRequired)
            return if (clean.length > MAX_CONTENT) Result.failure(TextTooLong(MAX_CONTENT)) else Result.success(clean)
        }
    }

    // No field here: the caller names it (forField), as for the shared failures beside them.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object TitleRequired : Failure("PAGE_TITLE_REQUIRED")
        data object ContentRequired : Failure("PAGE_CONTENT_REQUIRED")
    }
}
