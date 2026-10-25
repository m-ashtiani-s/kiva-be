package com.kiva.application.dto

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlockType
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import com.kiva.domain.aggregate.settings.model.valueobject.SocialLink
import java.time.Instant

/** A category of the blog (the contract's BlogCategory); [postCount] the posts the shop sees in it. */
data class BlogCategoryDto(val id: Long, val slug: String, val name: String, val postCount: Int)

/** A byline (the contract's BlogAuthor); its initial is the HTTP layer's. */
data class BlogAuthorDto(val id: Long, val name: String, val title: String?, val bio: String?, val avatar: UploadedMediaDto?)

/**
 * A post as cards and lists show it (the contract's BlogPostSummary).
 *
 * @property excerpt the editor's, or the start of the lead or first paragraph.
 * @property readingMinutes the editor's, or worked out from its words.
 * @property publishedAt null for a draft that was never given a date (the panel's only).
 */
data class BlogPostSummaryDto(
    val id: Long,
    val slug: String,
    val title: String,
    val excerpt: String?,
    val category: BlogCategoryDto,
    val cover: UploadedMediaDto?,
    val coverBackground: String?,
    val readingMinutes: Int,
    val publishedAt: Instant?,
    val author: BlogAuthorDto,
    val isFeatured: Boolean,
    val tags: List<String>,
)

/**
 * One block of a post as shown: its kind's fields, a product's card and a photo's file filled in. [productId] is the
 * product a PRODUCT block names, also when its [product] is not in the shop (the panel's warning).
 */
data class BlogBlockDto(
    val type: BlogBlockType,
    val anchor: String?,
    val text: String?,
    val html: String?,
    val title: String?,
    val items: List<String>?,
    val productId: Long?,
    val product: ProductCardDto?,
    val image: UploadedMediaDto?,
)

/**
 * A post's page (the contract's BlogPostDetail), without its Persian words and addresses (the HTTP layer writes the
 * breadcrumbs, the share links' text, the canonical address and the JSON-LD).
 *
 * @property shareChannels the shop's messengers whose link takes a text.
 */
data class BlogPostDetailDto(
    val summary: BlogPostSummaryDto,
    val lead: String?,
    val blocks: List<BlogBlockDto>,
    val helpfulCount: Int,
    val notHelpfulCount: Int,
    val seo: SeoMeta,
    val updatedOn: Instant,
    val shareChannels: List<SocialLink>,
)

/** A post in the panel (the contract's AdminBlogPost): its page with every block, and what only the panel sees. */
data class AdminBlogPostDto(
    val detail: BlogPostDetailDto,
    val status: BlogPostStatus,
    val scheduled: Boolean,
    val createdOn: Instant,
    val createdBy: ActorNameDto,
    val updatedBy: ActorNameDto,
    val unavailableProductIds: List<Long>,
    val feedbackComments: List<FeedbackComment>,
) {
    data class FeedbackComment(val comment: String, val createdOn: Instant) {
        override fun toString(): String = "FeedbackComment(createdOn=$createdOn)"
    }
}

/** The shop's list of posts (the contract's BlogPostListResponse); [featured] only on page one when it fits. */
data class BlogPostListDto(val featured: BlogPostSummaryDto?, val page: com.kiva.common.pagination.Page<BlogPostSummaryDto>)
