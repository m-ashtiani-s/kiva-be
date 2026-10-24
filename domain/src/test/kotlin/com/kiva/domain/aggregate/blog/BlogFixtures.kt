package com.kiva.domain.aggregate.blog

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlocks
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import java.time.Instant

/** A post of the blog as the panel's form would send it, and the post it makes. */
object BlogFixtures {
    val now: Instant = Instant.parse("2026-10-24T08:00:00Z")
    val editor: UserId = UserId.constructFromPersisted(30)
    val teamKiva: BlogAuthorId = BlogAuthorId.constructFromPersisted(1)

    /** Keeps the text, drops `<script>`: what the real sanitizer does to what these tests send. */
    val sanitizer = HtmlSanitizer { html -> html.replace(Regex("<script.*?</script>"), "") }

    fun block(
        type: String?,
        text: String? = null,
        html: String? = null,
        title: String? = null,
        items: List<String?>? = null,
        productId: Long? = null,
        imageMediaId: String? = null,
    ) = BlogBlocks.Raw(type, text, html, title, items, productId, imageMediaId)

    val heading = block("HEADING", text = "چرا فرم بدن مهمه؟")
    val paragraph = block("PARAGRAPH", text = "کیف فقط یه وسیله نیست؛ بخشی از تناسب ظاهریه.")

    fun fields(
        title: String? = "راهنمای انتخاب کیف بر اساس فرم بدن",
        slug: String? = "bag-by-body-shape",
        excerpt: String? = null,
        lead: String? = "قد، فرم شانه و سبک زندگیت تعیین می‌کنه چه کیفی بهت میاد.",
        categoryId: Long? = 1,
        authorId: Long? = null,
        blocks: List<BlogBlocks.Raw?>? = listOf(heading, paragraph),
        tags: List<String?>? = listOf("فرم بدن"),
        status: String? = null,
        readingMinutes: Int? = null,
        isFeatured: Boolean? = null,
    ) = BlogPostDraft.Fields(
        title, slug, excerpt, lead, categoryId, authorId, null, "#E4D9F3", readingMinutes, blocks, tags, isFeatured, status, null, null, null,
    )

    fun draft(fields: BlogPostDraft.Fields = fields(), publishedAt: Instant? = null): BlogPostDraft =
        BlogPostDraft.makeNew(fields, Result.success(publishedAt), sanitizer).getOrNull() ?: error("refused: ${BlogPostDraft.makeNew(fields, Result.success(publishedAt), sanitizer)}")

    fun post(draft: BlogPostDraft = draft(), id: Long = 12, at: Instant = now): BlogPost =
        BlogPost.create(BlogPostId.constructFromPersisted(id), draft, teamKiva, editor, at)
}
