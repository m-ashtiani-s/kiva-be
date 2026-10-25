package com.kiva.domain.aggregate.blog.entity

import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.policy.BlogPolicy
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlock
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import com.kiva.domain.aggregate.blog.model.valueobject.BlogTag
import com.kiva.domain.aggregate.blog.model.valueobject.CoverBackground
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * A post of «مجله‌ی کیوا» (roadmap phase 15, section 20.3). A plain row: its history is the change log's.
 *
 * Rules it keeps by itself:
 * - a published post always has its `publishedAt`: published without one, it is published now; published again
 *   without one, it keeps the date it was first published on (decision 19);
 * - the shop sees it only while PUBLISHED and once its `publishedAt` came ([isVisibleAt]): a later one is a schedule,
 *   with no job behind it (decision 3);
 * - its excerpt and reading time, when the editor left them empty, come from its own text (BlogPolicy).
 *
 * What it can't know: whether its slug is free, its category and author are there, its photos are the blog's and its
 * products in the shop, or which other post is featured (the use case, under the post's lock, asks the repository).
 */
class BlogPost private constructor(
    val id: BlogPostId,
    val slug: Slug,
    val title: String,
    val excerpt: String?,
    val lead: String?,
    val categoryId: BlogCategoryId,
    val authorId: BlogAuthorId,
    val coverMediaId: MediaId?,
    val coverBackground: CoverBackground?,
    val readingMinutes: Int?,
    val blocks: List<BlogBlock>,
    val tags: List<BlogTag>,
    val isFeatured: Boolean,
    val status: BlogPostStatus,
    val publishedAt: Instant?,
    val seo: SeoMeta,
    val createdOn: Instant,
    val createdBy: UserId,
    val updatedOn: Instant,
    val updatedBy: UserId,
) {
    init {
        // Second wall: create and rewrite always set it; only broken stored data gets here without it.
        require(status != BlogPostStatus.PUBLISHED || publishedAt != null) { "Blog post ${id.value} is published without a date" }
        require(blocks.isNotEmpty()) { "Blog post ${id.value} has no blocks" }
    }

    /** The shop shows it at [now]: published, and its date came. */
    fun isVisibleAt(now: Instant): Boolean = status == BlogPostStatus.PUBLISHED && !checkNotNull(publishedAt).isAfter(now)

    /** Published for a moment after [now]: waiting to be seen. */
    fun isScheduledAt(now: Instant): Boolean = status == BlogPostStatus.PUBLISHED && checkNotNull(publishedAt).isAfter(now)

    /** The editor's reading time, or the one its words give. */
    fun effectiveReadingMinutes(): Int = readingMinutes ?: BlogPolicy.readingMinutesOf(lead, blocks)

    /** The editor's excerpt, or the start of its lead or first paragraph. */
    fun effectiveExcerpt(): String? = excerpt ?: BlogPolicy.excerptFallback(lead, blocks)

    /** The table of contents: its headings, in order, each with its anchor (decision 8). */
    fun toc(): List<BlogBlock.Heading> = blocks.filterIsInstance<BlogBlock.Heading>()

    /** The products its blocks show, in order, each once. */
    fun productIds(): List<ProductId> = blocks.filterIsInstance<BlogBlock.ProductCard>().map { it.productId }.distinct()

    /** Its cover and its blocks' photos, each once: what is attached to it. */
    fun mediaIds(): List<MediaId> = (listOfNotNull(coverMediaId) + blocks.filterIsInstance<BlogBlock.Image>().map { it.mediaId }).distinct()

    /** The post as the form now has it, by [by] at [now]; its id and when it was made stay. */
    fun rewrite(draft: BlogPostDraft, authorId: BlogAuthorId, by: UserId, now: Instant): BlogPost =
        of(id, draft, authorId, publishedAtOf(draft, publishedBefore = publishedAt.takeIf { status == BlogPostStatus.PUBLISHED }, now), createdOn, createdBy, now, by)

    /** Not the featured one any more: another post took it (decision 5). */
    fun unfeatured(): BlogPost = if (!isFeatured) this else BlogPost(
        id, slug, title, excerpt, lead, categoryId, authorId, coverMediaId, coverBackground, readingMinutes, blocks, tags, false, status,
        publishedAt, seo, createdOn, createdBy, updatedOn, updatedBy,
    )

    override fun equals(other: Any?): Boolean = other is BlogPost && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "BlogPost(id=${id.value}, slug=${slug.value}, status=$status)"

    companion object {
        /** A new post of [draft], by [by] at [now], written by [authorId] (the default author when the form named none). */
        fun create(id: BlogPostId, draft: BlogPostDraft, authorId: BlogAuthorId, by: UserId, now: Instant): BlogPost =
            of(id, draft, authorId, publishedAtOf(draft, publishedBefore = null, now), now, by, now, by)

        /**
         * When it is published (decision 3, and 19): the form's date; published without one, the date it was published on
         * before, or now.
         */
        private fun publishedAtOf(draft: BlogPostDraft, publishedBefore: Instant?, now: Instant): Instant? =
            draft.publishedAt ?: if (draft.status == BlogPostStatus.PUBLISHED) publishedBefore ?: now else null

        private fun of(
            id: BlogPostId,
            draft: BlogPostDraft,
            authorId: BlogAuthorId,
            publishedAt: Instant?,
            createdOn: Instant,
            createdBy: UserId,
            updatedOn: Instant,
            updatedBy: UserId,
        ) = BlogPost(
            id, draft.slug, draft.title, draft.excerpt, draft.lead, draft.categoryId, authorId, draft.coverMediaId, draft.coverBackground,
            draft.readingMinutes, draft.blocks, draft.tags, draft.isFeatured, draft.status, publishedAt, draft.seo, createdOn, createdBy, updatedOn,
            updatedBy,
        )

        fun constructFromPersisted(
            id: BlogPostId,
            slug: Slug,
            title: String,
            excerpt: String?,
            lead: String?,
            categoryId: BlogCategoryId,
            authorId: BlogAuthorId,
            coverMediaId: MediaId?,
            coverBackground: CoverBackground?,
            readingMinutes: Int?,
            blocks: List<BlogBlock>,
            tags: List<BlogTag>,
            isFeatured: Boolean,
            status: BlogPostStatus,
            publishedAt: Instant?,
            seo: SeoMeta,
            createdOn: Instant,
            createdBy: UserId,
            updatedOn: Instant,
            updatedBy: UserId,
        ): BlogPost = BlogPost(
            id, slug, title, excerpt, lead, categoryId, authorId, coverMediaId, coverBackground, readingMinutes, blocks, tags, isFeatured, status,
            publishedAt, seo, createdOn, createdBy, updatedOn, updatedBy,
        )
    }
}
