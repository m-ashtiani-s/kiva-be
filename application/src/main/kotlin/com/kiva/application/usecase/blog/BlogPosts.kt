package com.kiva.application.usecase.blog

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.AdminBlogAuthorDto
import com.kiva.application.dto.AdminBlogPostDto
import com.kiva.application.dto.BlogAuthorDto
import com.kiva.application.dto.BlogBlockDto
import com.kiva.application.dto.BlogCategoryDto
import com.kiva.application.dto.BlogPostDetailDto
import com.kiva.application.dto.BlogPostSummaryDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductCards
import com.kiva.application.usecase.product.usecase.query.GetProductInquiryUc
import com.kiva.common.text.normalizePersian
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlock
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlockType
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.TextOrHtml
import com.kiva.domain.aggregate.blog.repository.BlogAuthorRepository
import com.kiva.domain.aggregate.blog.repository.BlogCategoryRepository
import com.kiva.domain.aggregate.blog.repository.BlogFeedbackRepository
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Instant

/**
 * The blog's posts as the shop and the panel show them (Rule 15): the same card, page and panel view for every use
 * case. Each call reads what it needs for all its posts at once (categories, authors, photos, product cards, votes,
 * who saved them), never once per post. Runs inside the caller's transaction.
 *
 * Product cards are the shop's (ProductCards), without a heart: the shop's answers are cached. A PRODUCT block whose
 * product is not in the shop any more is left out of the shop's page (decision 9) and kept in the panel's, with its id
 * in `unavailableProductIds`.
 */
class BlogPosts(
    private val blogPostRepository: BlogPostRepository,
    private val blogCategoryRepository: BlogCategoryRepository,
    private val blogAuthorRepository: BlogAuthorRepository,
    private val blogFeedbackRepository: BlogFeedbackRepository,
    private val mediaRepository: MediaRepository,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val adminCustomerRepository: AdminCustomerRepository,
) {

    /** The cards of [posts], in their order. */
    fun summariesOf(posts: List<BlogPost>, now: Instant): List<BlogPostSummaryDto> {
        if (posts.isEmpty()) return emptyList()
        val categories = categoriesAt(now)
        val authors = blogAuthorRepository.findAll(posts.map { it.authorId }.distinct())
        val media = mediaOf(posts.mapNotNull { it.coverMediaId } + authors.values.mapNotNull { it.avatarMediaId })
        return posts.map { post ->
            val author = authors[post.authorId] ?: error("Blog post ${post.id.value} has no author ${post.authorId.value}")
            BlogPostSummaryDto(
                id = post.id.value,
                slug = post.slug.value,
                title = post.title,
                excerpt = post.effectiveExcerpt(),
                category = categories[post.categoryId] ?: error("Blog post ${post.id.value} has no category ${post.categoryId.value}"),
                cover = post.coverMediaId?.let(media::get),
                coverBackground = post.coverBackground?.value,
                readingMinutes = post.effectiveReadingMinutes(),
                publishedAt = post.publishedAt,
                author = BlogAuthorDto(author.id.value, author.name, author.title, author.bio, author.avatarMediaId?.let(media::get)),
                isFeatured = post.isFeatured,
                tags = post.tags.map { it.value },
            )
        }
    }

    /** The shop's page of [post]: a PRODUCT block of a product not in the shop is left out. */
    fun pageOf(post: BlogPost, now: Instant): BlogPostDetailDto = detailOf(post, now, keepUnavailable = false).first

    /** The panel's view of [post]: every block, what is unavailable, and the last «no» comments. */
    fun adminOf(post: BlogPost, now: Instant): AdminBlogPostDto {
        val (detail, unavailable) = detailOf(post, now, keepUnavailable = true)
        val comments = blogFeedbackRepository.lastComments(post.id, FEEDBACK_COMMENTS).map { AdminBlogPostDto.FeedbackComment(it.text, it.createdOn) }
        return adminDto(post, detail, unavailable, comments, accountsOf(listOf(post)), now)
    }

    /** The panel's list rows: each post without its blocks (decision 2 of section 20.6), all in a few reads. */
    fun adminRowsOf(posts: List<BlogPost>, now: Instant): List<AdminBlogPostDto> {
        if (posts.isEmpty()) return emptyList()
        val summaries = summariesOf(posts, now)
        val stats = blogFeedbackRepository.statsOfAll(posts.map { it.id })
        val inShop = productQmRepository.findCards(posts.flatMap { it.productIds() }.distinct()).map { it.productId }.toSet()
        val accounts = accountsOf(posts)
        val shareChannels = shareChannels()
        return posts.zip(summaries) { post, summary ->
            val votes = stats[post.id] ?: BlogFeedbackRepository.Stats(0, 0)
            val detail = BlogPostDetailDto(summary, post.lead, emptyList(), votes.helpful, votes.notHelpful, post.seo, post.updatedOn, shareChannels)
            adminDto(post, detail, post.productIds().filterNot { it in inShop }.map { it.value }, emptyList(), accounts, now)
        }
    }

    /** Every author for the panel's picker, the default first, their avatars in one read. */
    fun authors(): List<AdminBlogAuthorDto> {
        val authors = blogAuthorRepository.findAll()
        val media = mediaOf(authors.mapNotNull { it.avatarMediaId })
        return authors.map { author ->
            AdminBlogAuthorDto(BlogAuthorDto(author.id.value, author.name, author.title, author.bio, author.avatarMediaId?.let(media::get)), author.isDefault)
        }
    }

    /** What `q` finds a post by (decision 10): its title, excerpt, tags and its category's name, normalized. */
    fun searchTextOf(post: BlogPost, categoryName: String): String =
        listOfNotNull(post.title, post.effectiveExcerpt(), categoryName).plus(post.tags.map { it.value }).joinToString(" ").normalizePersian()

    /** Every category with the posts the shop sees in it at [now], by id. */
    fun categoriesAt(now: Instant): Map<BlogCategoryId, BlogCategoryDto> {
        val counts = blogPostRepository.countVisibleByCategory(now)
        return blogCategoryRepository.findAll().associate { it.id to BlogCategoryDto(it.id.value, it.slug.value, it.name, counts[it.id] ?: 0) }
    }

    private fun detailOf(post: BlogPost, now: Instant, keepUnavailable: Boolean): Pair<BlogPostDetailDto, List<Long>> {
        val summary = summariesOf(listOf(post), now).single()
        val cards = cardsOf(post.productIds(), now)
        val images = mediaOf(post.blocks.filterIsInstance<BlogBlock.Image>().map { it.mediaId })
        val unavailable = post.productIds().filterNot { it in cards }.map { it.value }
        val blocks = post.blocks.mapNotNull { block ->
            if (block is BlogBlock.ProductCard && block.productId !in cards && !keepUnavailable) null else blockOf(block, cards, images)
        }
        val stats = blogFeedbackRepository.statsOf(post.id)
        return BlogPostDetailDto(summary, post.lead, blocks, stats.helpful, stats.notHelpful, post.seo, post.updatedOn, shareChannels()) to unavailable
    }

    private fun adminDto(
        post: BlogPost,
        detail: BlogPostDetailDto,
        unavailable: List<Long>,
        comments: List<AdminBlogPostDto.FeedbackComment>,
        accounts: Map<com.kiva.common.valueobject.UserId, com.kiva.domain.aggregate.user.entity.User>,
        now: Instant,
    ) = AdminBlogPostDto(
        detail = detail,
        status = post.status,
        scheduled = post.isScheduledAt(now),
        createdOn = post.createdOn,
        createdBy = ActorNameDto.of(post.createdBy, accounts[post.createdBy]),
        updatedBy = ActorNameDto.of(post.updatedBy, accounts[post.updatedBy]),
        unavailableProductIds = unavailable,
        feedbackComments = comments,
    )

    private fun accountsOf(posts: List<BlogPost>) = adminCustomerRepository.findAll(posts.flatMap { listOf(it.createdBy, it.updatedBy) }.distinct())

    /** The shop's messengers whose link takes a text (the same as «سؤال داری؟»'s, decision 12). */
    private fun shareChannels() = shopSettingsRepository.get().social.filter { it.channel in GetProductInquiryUc.PREFILLED }

    private fun blockOf(block: BlogBlock, cards: Map<ProductId, ProductCardDto>, images: Map<MediaId, UploadedMediaDto>): BlogBlockDto = when (block) {
        is BlogBlock.Heading -> dto(BlogBlockType.HEADING, anchor = block.anchor, text = block.text)
        is BlogBlock.Paragraph -> content(BlogBlockType.PARAGRAPH, null, block.content)
        is BlogBlock.Quote -> dto(BlogBlockType.QUOTE, text = block.text)
        is BlogBlock.Tip -> content(BlogBlockType.TIP, block.title, block.content)
        is BlogBlock.ProductCard -> dto(BlogBlockType.PRODUCT, productId = block.productId.value, product = cards[block.productId])
        is BlogBlock.Image -> dto(BlogBlockType.IMAGE, text = block.caption, image = images[block.mediaId])
        is BlogBlock.ListBlock -> dto(BlogBlockType.LIST, items = block.items)
    }

    private fun content(type: BlogBlockType, title: String?, content: TextOrHtml) = when (content) {
        is TextOrHtml.Text -> dto(type, title = title, text = content.value)
        is TextOrHtml.Html -> dto(type, title = title, html = content.value)
    }

    private fun dto(
        type: BlogBlockType,
        anchor: String? = null,
        text: String? = null,
        html: String? = null,
        title: String? = null,
        items: List<String>? = null,
        productId: Long? = null,
        product: ProductCardDto? = null,
        image: UploadedMediaDto? = null,
    ) = BlogBlockDto(type, anchor, text, html, title, items, productId, product, image)

    /** The shop's cards of those of [ids] in the shop now, by id. */
    private fun cardsOf(ids: List<ProductId>, now: Instant): Map<ProductId, ProductCardDto> {
        val found = productQmRepository.findCards(ids)
        if (found.isEmpty()) return emptyMap()
        val categories = categoryRepository.findAll().associateBy { it.slug }
        val palette = colorRepository.findAll().associateBy { it.key }
        val settings = shopSettingsRepository.get()
        return found.associate { qm ->
            qm.productId to ProductCards.cardOf(qm, categories.getValue(qm.categorySlug), palette, now, settings.freeShippingThreshold, settings.lowStockThreshold)
        }
    }

    private fun mediaOf(ids: List<MediaId>): Map<MediaId, UploadedMediaDto> {
        if (ids.isEmpty()) return emptyMap()
        return mediaRepository.findAll(ids.distinct()).associate { it.id to UploadedMediaDto.from(it) }
    }

    companion object {
        /** The panel's last «مفید نبود» comments of a post (decision 15). */
        const val FEEDBACK_COMMENTS = 50
    }
}
