package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlocks
import kotlinx.serialization.Serializable

/**
 * The body of `POST`/`PUT /admin/blog/posts*` (the contract's BlogPostUpsertRequest), as it arrives: every field
 * nullable, so the use case reports every missing one at once. `seo`'s `canonicalUrl`, `ogImageUrl` and `jsonLd` are the
 * server's and not read.
 */
@Serializable
data class BlogPostHttpRequest(
    val title: String? = null,
    val slug: String? = null,
    val excerpt: String? = null,
    val lead: String? = null,
    val categoryId: Long? = null,
    val authorId: Long? = null,
    val coverMediaId: String? = null,
    val coverBackground: String? = null,
    val readingMinutes: Int? = null,
    val blocks: List<Block?>? = null,
    val tags: List<String?>? = null,
    val isFeatured: Boolean? = null,
    val status: String? = null,
    val publishedAt: String? = null,
    val seo: SeoHttpRequest? = null,
) {
    /** One block as the form sends it: the fields of every kind; only its own kind's are kept. */
    @Serializable
    data class Block(
        val type: String? = null,
        val text: String? = null,
        val html: String? = null,
        val title: String? = null,
        val items: List<String?>? = null,
        val productId: Long? = null,
        val imageMediaId: String? = null,
    )

    fun toFields() = BlogPostDraft.Fields(
        title = title,
        slug = slug,
        excerpt = excerpt,
        lead = lead,
        categoryId = categoryId,
        authorId = authorId,
        coverMediaId = coverMediaId,
        coverBackground = coverBackground,
        readingMinutes = readingMinutes,
        blocks = blocks?.map { block -> block?.let { BlogBlocks.Raw(it.type, it.text, it.html, it.title, it.items, it.productId, it.imageMediaId) } },
        tags = tags,
        isFeatured = isFeatured,
        status = status,
        seoTitle = seo?.title,
        seoDescription = seo?.description,
        seoNoIndex = seo?.noIndex,
    )

    override fun toString(): String = "BlogPostHttpRequest(slug=$slug, status=$status, blocks=${blocks?.size})"
}

/** The body of `POST /blog/posts/{postId}/feedback`. */
@Serializable
data class BlogFeedbackHttpRequest(val helpful: Boolean? = null, val comment: String? = null) {
    // A comment may say anything.
    override fun toString(): String = "BlogFeedbackHttpRequest(helpful=$helpful)"
}
