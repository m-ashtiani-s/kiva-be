package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.AdminBlogAuthorDto
import com.kiva.application.dto.AdminBlogPostDto
import com.kiva.application.dto.BlogAuthorDto
import com.kiva.application.dto.BlogBlockDto
import com.kiva.application.dto.BlogCategoryDto
import com.kiva.application.dto.BlogPostDetailDto
import com.kiva.application.dto.BlogPostListDto
import com.kiva.application.dto.BlogPostSummaryDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The blog's Persian words (roadmap phase 15): the default tip title, the magazine's name, the breadcrumbs, the empty list. */
object BlogTexts {
    const val TIP_TITLE = "نکته کیوا"
    const val MAGAZINE = "مجله"
    const val HOME = "خانه"
    const val EMPTY = "مقاله‌ای با این مشخصات پیدا نشد."
    const val SITE_NAME = "کیوا"

    /** The text a messenger link carries: «{title} {address}» (decision 12). */
    fun shareText(title: String, url: String): String = "$title $url"

    /** «{title} | مجله‌ی کیوا», when the editor gave no title for search engines. */
    fun seoTitle(title: String): String = "$title | مجله‌ی کیوا"
}

/** Contract schema `BlogCategory`. */
@Serializable
data class BlogCategoryHttpResponse(val id: Long, val slug: String, val name: String, val postCount: Int) {
    companion object {
        fun from(category: BlogCategoryDto) = BlogCategoryHttpResponse(category.id, category.slug, category.name, category.postCount)
    }
}

/** Contract schema `BlogAuthor`; [initial] is the name's first letter. */
@Serializable
data class BlogAuthorHttpResponse(val id: Long, val name: String, val title: String?, val initial: String, val avatarUrl: String?, val bio: String?) {
    companion object {
        fun from(author: BlogAuthorDto, urls: MediaUrls) = BlogAuthorHttpResponse(
            id = author.id,
            name = author.name,
            title = author.title,
            initial = author.name.trim().take(1),
            avatarUrl = author.avatar?.let { urls.of(it.thumbnailPath) },
            bio = author.bio,
        )
    }
}

/** Contract schema `AdminBlogAuthor`: every field of `BlogAuthor`, and whether a post without `authorId` gets this one. */
@Serializable
data class AdminBlogAuthorHttpResponse(
    val id: Long,
    val name: String,
    val title: String?,
    val initial: String,
    val avatarUrl: String?,
    val bio: String?,
    val isDefault: Boolean,
) {
    companion object {
        fun from(admin: AdminBlogAuthorDto, urls: MediaUrls): AdminBlogAuthorHttpResponse {
            val a = BlogAuthorHttpResponse.from(admin.author, urls)
            return AdminBlogAuthorHttpResponse(a.id, a.name, a.title, a.initial, a.avatarUrl, a.bio, admin.isDefault)
        }
    }
}

/** Contract schema `BlogPostSummary`: every field always there. */
@Serializable
data class BlogPostSummaryHttpResponse(
    val id: Long,
    val slug: String,
    val title: String,
    val excerpt: String?,
    val category: BlogCategoryHttpResponse,
    val cover: MediaAssetHttpResponse?,
    val coverBackground: String?,
    val readingMinutes: Int,
    val publishedAt: String?,
    val author: BlogAuthorHttpResponse,
    val isFeatured: Boolean,
    val tags: List<String>,
) {
    companion object {
        fun from(post: BlogPostSummaryDto, urls: MediaUrls) = BlogPostSummaryHttpResponse(
            id = post.id,
            slug = post.slug,
            title = post.title,
            excerpt = post.excerpt,
            category = BlogCategoryHttpResponse.from(post.category),
            cover = post.cover?.let { MediaAssetHttpResponse.from(it, urls) },
            coverBackground = post.coverBackground,
            readingMinutes = post.readingMinutes,
            publishedAt = post.publishedAt?.toString(),
            author = BlogAuthorHttpResponse.from(post.author, urls),
            isFeatured = post.isFeatured,
            tags = post.tags,
        )
    }
}

/** Contract schema `BlogContentBlock`: every field always there, those of other kinds null. */
@Serializable
data class BlogContentBlockHttpResponse(
    val type: String,
    val anchor: String?,
    val text: String?,
    val html: String?,
    val title: String?,
    val items: List<String>?,
    val product: ProductSummaryHttpResponse?,
    val image: MediaAssetHttpResponse?,
) {
    companion object {
        fun from(block: BlogBlockDto, urls: MediaUrls) = BlogContentBlockHttpResponse(
            type = block.type.name,
            anchor = block.anchor,
            text = block.text,
            html = block.html,
            // A tip without its own title is «نکته کیوا» (decision 7).
            title = block.title ?: BlogTexts.TIP_TITLE.takeIf { block.type.name == "TIP" },
            items = block.items,
            product = block.product?.let { ProductSummaryHttpResponse.from(it, emptyList(), urls) },
            image = block.image?.let { MediaAssetHttpResponse.from(it, urls) },
        )
    }
}

@Serializable
data class BlogTocEntryHttpResponse(val anchor: String, val title: String)

@Serializable
data class BlogHelpfulStatsHttpResponse(val helpfulCount: Int, val notHelpfulCount: Int)

/**
 * Contract schema `SeoMeta` of a post (decision 6): the editor's title and description, or the post's own; its address
 * on the site, its cover's 1200 wide copy, and an `Article` for search engines, all the server's.
 */
@Serializable
data class BlogSeoHttpResponse(
    val title: String,
    val description: String?,
    val canonicalUrl: String,
    val ogImageUrl: String?,
    val noIndex: Boolean,
    val jsonLd: JsonObject,
)

/** Contract schema `BlogPostDetail`: the summary's fields and the page's. */
@Serializable
data class BlogPostDetailHttpResponse(
    val id: Long,
    val slug: String,
    val title: String,
    val excerpt: String?,
    val category: BlogCategoryHttpResponse,
    val cover: MediaAssetHttpResponse?,
    val coverBackground: String?,
    val readingMinutes: Int,
    val publishedAt: String?,
    val author: BlogAuthorHttpResponse,
    val isFeatured: Boolean,
    val tags: List<String>,
    val lead: String?,
    val blocks: List<BlogContentBlockHttpResponse>,
    val toc: List<BlogTocEntryHttpResponse>,
    val breadcrumbs: List<BreadcrumbHttpResponse>,
    val shareUrl: String,
    val shareLinks: List<MessengerLinkHttpResponse>,
    val helpfulStats: BlogHelpfulStatsHttpResponse,
    val seo: BlogSeoHttpResponse,
    val updatedAt: String,
) {
    companion object {
        fun from(page: BlogPostDetailDto, siteUrl: String, urls: MediaUrls): BlogPostDetailHttpResponse {
            val post = page.summary
            val url = "${siteUrl.trimEnd('/')}/blog/${post.slug}"
            val share = BlogTexts.shareText(post.title, url)
            return BlogPostDetailHttpResponse(
                id = post.id,
                slug = post.slug,
                title = post.title,
                excerpt = post.excerpt,
                category = BlogCategoryHttpResponse.from(post.category),
                cover = post.cover?.let { MediaAssetHttpResponse.from(it, urls) },
                coverBackground = post.coverBackground,
                readingMinutes = post.readingMinutes,
                publishedAt = post.publishedAt?.toString(),
                author = BlogAuthorHttpResponse.from(post.author, urls),
                isFeatured = post.isFeatured,
                tags = post.tags,
                lead = page.lead,
                blocks = page.blocks.map { BlogContentBlockHttpResponse.from(it, urls) },
                toc = page.blocks.filter { it.anchor != null }.map { BlogTocEntryHttpResponse(it.anchor!!, it.text.orEmpty()) },
                breadcrumbs = listOf(
                    BreadcrumbHttpResponse(BlogTexts.HOME, "/"),
                    BreadcrumbHttpResponse(BlogTexts.MAGAZINE, "/blog"),
                    BreadcrumbHttpResponse(post.category.name, "/blog?category=${post.category.slug}"),
                    BreadcrumbHttpResponse(post.title),
                ),
                shareUrl = url,
                shareLinks = page.shareChannels.map { MessengerLinkHttpResponse.from(it, share, null) },
                helpfulStats = BlogHelpfulStatsHttpResponse(page.helpfulCount, page.notHelpfulCount),
                seo = seoOf(page, url, urls),
                updatedAt = page.updatedOn.toString(),
            )
        }

        private fun seoOf(page: BlogPostDetailDto, url: String, urls: MediaUrls): BlogSeoHttpResponse {
            val post = page.summary
            val image = post.cover?.let { ogImageOf(it, urls) }
            return BlogSeoHttpResponse(
                title = page.seo.title ?: BlogTexts.seoTitle(post.title),
                description = page.seo.description ?: post.excerpt,
                canonicalUrl = url,
                ogImageUrl = image,
                noIndex = page.seo.noIndex,
                jsonLd = buildJsonObject {
                    put("@context", "https://schema.org")
                    put("@type", "Article")
                    put("headline", post.title)
                    post.excerpt?.let { put("description", it) }
                    image?.let { put("image", it) }
                    post.publishedAt?.let { put("datePublished", it.toString()) }
                    put("dateModified", page.updatedOn.toString())
                    putJsonObject("author") {
                        put("@type", "Organization")
                        put("name", post.author.name)
                    }
                    putJsonObject("publisher") {
                        put("@type", "Organization")
                        put("name", BlogTexts.SITE_NAME)
                    }
                    put("mainEntityOfPage", url)
                },
            )
        }

        /** The cover's copy 1200 wide, or its largest under that, or its main file. */
        private fun ogImageOf(cover: UploadedMediaDto, urls: MediaUrls): String =
            urls.of(cover.variants.filter { it.width <= OG_WIDTH }.maxByOrNull { it.width }?.path ?: cover.mainPath)

        private const val OG_WIDTH = 1200
    }
}

/**
 * Contract schema `AdminBlogPost`: the page with every block, and what only the panel sees. `excerpt`,
 * `readingMinutes` and `seo` are what the site shows (fallbacks filled in); [excerptInput], [readingMinutesInput] and
 * [seoInput] are the form's own values as saved, which a `PUT` sends back unchanged.
 */
@Serializable
data class AdminBlogPostHttpResponse(
    val id: Long,
    val slug: String,
    val title: String,
    val excerpt: String?,
    val category: BlogCategoryHttpResponse,
    val cover: MediaAssetHttpResponse?,
    val coverBackground: String?,
    val readingMinutes: Int,
    val publishedAt: String?,
    val author: BlogAuthorHttpResponse,
    val isFeatured: Boolean,
    val tags: List<String>,
    val lead: String?,
    val blocks: List<BlogContentBlockHttpResponse>,
    val toc: List<BlogTocEntryHttpResponse>,
    val breadcrumbs: List<BreadcrumbHttpResponse>,
    val shareUrl: String,
    val shareLinks: List<MessengerLinkHttpResponse>,
    val helpfulStats: BlogHelpfulStatsHttpResponse,
    val seo: BlogSeoHttpResponse,
    val updatedAt: String,
    val excerptInput: String?,
    val readingMinutesInput: Int?,
    val seoInput: SeoInputHttpResponse,
    val status: String,
    val scheduled: Boolean,
    val createdAt: String,
    val createdBy: String,
    val updatedBy: String,
    val unavailableProductIds: List<Long>,
    val feedbackComments: List<FeedbackComment>,
) {
    @Serializable
    data class FeedbackComment(val comment: String, val createdAt: String)

    companion object {
        fun from(post: AdminBlogPostDto, siteUrl: String, urls: MediaUrls): AdminBlogPostHttpResponse {
            val page = BlogPostDetailHttpResponse.from(post.detail, siteUrl, urls)
            return AdminBlogPostHttpResponse(
                page.id, page.slug, page.title, page.excerpt, page.category, page.cover, page.coverBackground, page.readingMinutes, page.publishedAt,
                page.author, page.isFeatured, page.tags, page.lead, page.blocks, page.toc, page.breadcrumbs, page.shareUrl, page.shareLinks,
                page.helpfulStats, page.seo, page.updatedAt,
                excerptInput = post.excerpt,
                readingMinutesInput = post.readingMinutes,
                seoInput = SeoInputHttpResponse.from(post.detail.seo),
                status = post.status.name,
                scheduled = post.scheduled,
                createdAt = post.createdOn.toString(),
                createdBy = nameOf(post.createdBy),
                updatedBy = nameOf(post.updatedBy),
                unavailableProductIds = post.unavailableProductIds,
                feedbackComments = post.feedbackComments.map { FeedbackComment(it.comment, it.createdOn.toString()) },
            )
        }

        private fun nameOf(actor: ActorNameDto): String = PanelTexts.actorName(actor)
    }
}

/** Contract schema `BlogPostListResponse`: the featured post on page one when it fits, and an empty list's message. */
@Serializable
data class BlogPostListHttpResponse(
    val featured: BlogPostSummaryHttpResponse?,
    val items: List<BlogPostSummaryHttpResponse>,
    val meta: PageMetaHttpResponse,
    val emptyMessage: String?,
) {
    companion object {
        fun from(list: BlogPostListDto, urls: MediaUrls): BlogPostListHttpResponse {
            val page = list.page.toHttpResponse { BlogPostSummaryHttpResponse.from(it, urls) }
            return BlogPostListHttpResponse(
                featured = list.featured?.let { BlogPostSummaryHttpResponse.from(it, urls) },
                items = page.items,
                meta = page.meta,
                emptyMessage = BlogTexts.EMPTY.takeIf { list.featured == null && page.items.isEmpty() },
            )
        }
    }
}
