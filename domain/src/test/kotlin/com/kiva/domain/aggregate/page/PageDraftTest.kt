package com.kiva.domain.aggregate.page

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.page.entity.StaticPage
import com.kiva.domain.aggregate.page.model.PageDraft
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A static page's form and its rewrite (roadmap phase 5, step 5.1, decision 7). */
class PageDraftTest {

    /** Drops `<script>…</script>`, as the real sanitizer would; everything else stays. */
    private val sanitizer = HtmlSanitizer { it.replace(Regex("<script>.*?</script>"), "") }

    private fun draft(
        title: String? = "قوانین و حریم خصوصی",
        content: String? = "<p>متن قوانین</p>",
        seoTitle: String? = null,
        seoDescription: String? = null,
    ) = PageDraft.makeNew(title, content, seoTitle, seoDescription, null, sanitizer)

    private fun problemsOf(result: Result<PageDraft, ResultFailure>): List<String> =
        result.failureOrNull()?.causes.orEmpty().map { "${it.field} ${it.code}" }

    @Test
    fun `a title and some content make a page - the title trimmed, Arabic letters made Persian, the content cleaned`() {
        val page = draft(title = "  قوانين كيوا ", content = "<p>متن</p><script>alert(1)</script>").getOrNull()!!

        assertEquals("قوانین کیوا", page.title)
        assertEquals("<p>متن</p>", page.content)
        assertEquals(SeoMeta.NONE, page.seo)
    }

    @Test
    fun `no title, no content and content that is only a script - each on its own field, all at once`() {
        assertEquals(listOf("title PAGE_TITLE_REQUIRED", "content PAGE_CONTENT_REQUIRED"), problemsOf(draft(title = " ", content = "<script>x</script>")))
        assertEquals(listOf("title PAGE_TITLE_REQUIRED", "content PAGE_CONTENT_REQUIRED"), problemsOf(draft(title = null, content = null)))
    }

    @Test
    fun `a title over 120 and content over 50,000 after cleaning are TEXT_TOO_LONG with their limits`() {
        val result = draft(title = "ق".repeat(121), content = "<p>" + "م".repeat(50_000) + "</p>")

        assertEquals(listOf("title TEXT_TOO_LONG", "content TEXT_TOO_LONG"), problemsOf(result))
        assertEquals(listOf(120, 50_000), result.failureOrNull()!!.causes.map { it.meta["maxLength"] })
    }

    @Test
    fun `content is measured after cleaning - a script that made it too long doesn't count`() {
        val content = "<p>" + "م".repeat(49_000) + "</p><script>" + "x".repeat(5_000) + "</script>"

        assertNotNull(draft(content = content).getOrNull())
    }

    @Test
    fun `search engine fields over their limits are 400 on seo dot title and seo dot description`() {
        assertEquals(listOf("seo.title SEO_TITLE_TOO_LONG", "seo.description SEO_DESCRIPTION_TOO_LONG"), problemsOf(draft(seoTitle = "ع".repeat(71), seoDescription = "ت".repeat(171))))
    }

    @Test
    fun `rewriting with the same draft changes nothing - another draft is the page by its new writer at the new time`() {
        val now = Instant.parse("2026-10-21T08:00:00Z")
        val first = draft().getOrNull()!!
        val page = StaticPage.write(Slug.constructFromPersisted("terms"), first, UserId.constructFromPersisted(5), now)

        assertNull(page.rewrite(draft().getOrNull()!!, UserId.constructFromPersisted(6), now.plusSeconds(60)))

        val rewritten = page.rewrite(draft(title = "قوانین").getOrNull()!!, UserId.constructFromPersisted(6), now.plusSeconds(60))!!
        assertEquals(listOf("قوانین", "6", now.plusSeconds(60).toString()), listOf(rewritten.draft.title, rewritten.updatedBy.value.toString(), rewritten.updatedOn.toString()))
    }
}
