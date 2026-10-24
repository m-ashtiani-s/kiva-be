package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlock
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlockType
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import com.kiva.domain.aggregate.blog.model.valueobject.BlogTag
import com.kiva.domain.aggregate.blog.model.valueobject.CoverBackground
import com.kiva.domain.aggregate.blog.model.valueobject.TextOrHtml
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository.AdminFilter
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository.PublicFilter
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.persistence.exposed.tbl.BlogBlockPayload
import com.kiva.infrastructure.persistence.exposed.tbl.BlogPostTbl
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.postgresql.util.PSQLException
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class BlogPostRepositoryImpl : BlogPostRepository {

    override fun nextId(): BlogPostId =
        TransactionManager.current().exec("SELECT nextval('${BlogPostTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            BlogPostId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of ${BlogPostTbl.ID_SEQUENCE} returned nothing")

    override fun add(post: BlogPost, searchText: String) {
        refusingTakenSlug {
            BlogPostTbl.insert {
                it[postId] = post.id.value
                it[createdOn] = post.createdOn.utc()
                it[createdBy] = post.createdBy.value
                it.write(post, searchText)
            }
        }
    }

    override fun save(post: BlogPost, searchText: String) {
        val updated = refusingTakenSlug { BlogPostTbl.update({ BlogPostTbl.postId eq post.id.value }) { it.write(post, searchText) } }
        check(updated == 1) { "Blog post ${post.id.value}: updated $updated rows instead of 1" }
    }

    override fun find(id: BlogPostId): BlogPost? = BlogPostTbl.selectAll().where { BlogPostTbl.postId eq id.value }.singleOrNull()?.toPost()

    override fun lock(id: BlogPostId): BlogPost? =
        BlogPostTbl.selectAll().where { BlogPostTbl.postId eq id.value }.forUpdate().singleOrNull()?.toPost()

    override fun findBySlug(slug: Slug): BlogPost? = BlogPostTbl.selectAll().where { BlogPostTbl.slug eq slug.value }.singleOrNull()?.toPost()

    override fun isSlugTaken(slug: Slug, except: BlogPostId?): Boolean =
        !BlogPostTbl.select(BlogPostTbl.id)
            .where { (BlogPostTbl.slug eq slug.value).let { taken -> if (except == null) taken else taken and (BlogPostTbl.postId neq except.value) } }
            .limit(1)
            .empty()

    // Its votes go with it: blogfeedback_tbl's foreign key is ON DELETE CASCADE.
    override fun delete(id: BlogPostId) {
        BlogPostTbl.deleteWhere { BlogPostTbl.postId eq id.value }
    }

    override fun unfeatureAllExcept(id: BlogPostId) {
        BlogPostTbl.update({ BlogPostTbl.isFeatured and (BlogPostTbl.postId neq id.value) }) { it[isFeatured] = false }
    }

    override fun pagePublic(filter: PublicFilter, now: Instant, pageable: Pageable): Page<BlogPost> =
        page(BlogPostTbl.selectAll().where { seenAt(now) and publicCondition(filter) }, pageable, BlogPostTbl.publishedAt to SortOrder.DESC, BlogPostTbl.postId to SortOrder.DESC)

    override fun featured(filter: PublicFilter, now: Instant): BlogPost? =
        BlogPostTbl.selectAll().where { BlogPostTbl.isFeatured and seenAt(now) and publicCondition(filter) }.singleOrNull()?.toPost()

    override fun related(post: BlogPost, now: Instant, limit: Int): List<BlogPost> {
        val sameCategory = Case().When(BlogPostTbl.categoryId eq post.categoryId.value, intLiteral(0)).Else(intLiteral(1))
        return BlogPostTbl.selectAll()
            .where { seenAt(now) and (BlogPostTbl.postId neq post.id.value) }
            .orderBy(sameCategory to SortOrder.ASC, BlogPostTbl.publishedAt to SortOrder.DESC, BlogPostTbl.postId to SortOrder.DESC)
            .limit(limit)
            .map { it.toPost() }
    }

    override fun pageAdmin(filter: AdminFilter, pageable: Pageable): Page<BlogPost> {
        val conditions = buildList {
            filter.status?.let { add(BlogPostTbl.status eq it.name) }
            filter.categoryId?.let { add(BlogPostTbl.categoryId eq it.value) }
            addAll(filter.words.map { BlogPostTbl.searchText like containing(it) })
        }
        val query = BlogPostTbl.selectAll().where { conditions.reduceOrNull { all, next -> all and next } ?: Op.TRUE }
        return page(query, pageable, BlogPostTbl.updatedOn to SortOrder.DESC, BlogPostTbl.postId to SortOrder.DESC)
    }

    override fun countVisibleByCategory(now: Instant): Map<BlogCategoryId, Int> {
        val count = BlogPostTbl.postId.count()
        return BlogPostTbl.select(BlogPostTbl.categoryId, count)
            .where { seenAt(now) }
            .groupBy(BlogPostTbl.categoryId)
            .associate { BlogCategoryId.constructFromPersisted(it[BlogPostTbl.categoryId]) to it[count].toInt() }
    }

    /** One page of [query] in a fixed [order]; the last column is unique, so a page never repeats a row of another. */
    private fun page(query: Query, pageable: Pageable, vararg order: Pair<org.jetbrains.exposed.v1.core.Expression<*>, SortOrder>): Page<BlogPost> {
        val total = query.copy().count()
        val items = query.copy().orderBy(*order).limit(pageable.size).offset(pageable.offset).map { it.toPost() }
        return Page(items, pageable.page, pageable.size, total)
    }

    /** Seen by the shop at [now] (decision 3): published, and its moment came. */
    private fun seenAt(now: Instant): Op<Boolean> = (BlogPostTbl.status eq BlogPostStatus.PUBLISHED.name) and (BlogPostTbl.publishedAt lessEq now.utc())

    private fun publicCondition(filter: PublicFilter): Op<Boolean> {
        val conditions = buildList {
            filter.categoryId?.let { add(BlogPostTbl.categoryId eq it.value) }
            filter.tag?.let { add(BlogPostTbl.hasTag(it.value)) }
            filter.exclude?.let { add(BlogPostTbl.postId neq it.value) }
            addAll(filter.words.map { BlogPostTbl.searchText like containing(it) })
        }
        return conditions.reduceOrNull { all, next -> all and next } ?: Op.TRUE
    }

    private fun containing(word: String): LikePattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(word, ESCAPE) + "%"

    /** [write] refused by the slug's unique key: another post took it meanwhile. Any other refusal is a bug. */
    private fun <T> refusingTakenSlug(write: () -> T): T = try {
        write()
    } catch (e: ExposedSQLException) {
        if ((e.cause as? PSQLException)?.serverErrorMessage?.constraint == BlogPostTbl.SLUG_KEY) throw UniqueTakenException("slug", e)
        throw e
    }

    private fun UpdateBuilder<*>.write(post: BlogPost, searchText: String) {
        this[BlogPostTbl.slug] = post.slug.value
        this[BlogPostTbl.title] = post.title
        this[BlogPostTbl.excerpt] = post.excerpt
        this[BlogPostTbl.lead] = post.lead
        this[BlogPostTbl.categoryId] = post.categoryId.value
        this[BlogPostTbl.authorId] = post.authorId.value
        this[BlogPostTbl.coverMediaId] = post.coverMediaId?.value
        this[BlogPostTbl.coverBackground] = post.coverBackground?.value
        this[BlogPostTbl.readingMinutes] = post.readingMinutes?.toShort()
        this[BlogPostTbl.blocks] = post.blocks.map(::payloadOf)
        this[BlogPostTbl.tags] = post.tags.map { it.value }
        this[BlogPostTbl.searchText] = searchText
        this[BlogPostTbl.isFeatured] = post.isFeatured
        this[BlogPostTbl.status] = post.status.name
        this[BlogPostTbl.publishedAt] = post.publishedAt?.utc()
        this[BlogPostTbl.seoTitle] = post.seo.title
        this[BlogPostTbl.seoDescription] = post.seo.description
        this[BlogPostTbl.seoNoIndex] = post.seo.noIndex
        this[BlogPostTbl.updatedOn] = post.updatedOn.utc()
        this[BlogPostTbl.updatedBy] = post.updatedBy.value
    }

    private companion object {
        const val ESCAPE = '\\'
    }
}

private fun Instant.utc() = atOffset(ZoneOffset.UTC)

/** A block as stored: its kind and its own fields. */
internal fun payloadOf(block: BlogBlock): BlogBlockPayload = when (block) {
    is BlogBlock.Heading -> BlogBlockPayload(BlogBlockType.HEADING.name, anchor = block.anchor, text = block.text)
    is BlogBlock.Paragraph -> contentPayload(BlogBlockType.PARAGRAPH, null, block.content)
    is BlogBlock.Quote -> BlogBlockPayload(BlogBlockType.QUOTE.name, text = block.text)
    is BlogBlock.Tip -> contentPayload(BlogBlockType.TIP, block.title, block.content)
    is BlogBlock.ProductCard -> BlogBlockPayload(BlogBlockType.PRODUCT.name, productId = block.productId.value)
    is BlogBlock.Image -> BlogBlockPayload(BlogBlockType.IMAGE.name, imageMediaId = block.mediaId.value, text = block.caption)
    is BlogBlock.ListBlock -> BlogBlockPayload(BlogBlockType.LIST.name, items = block.items)
}

private fun contentPayload(type: BlogBlockType, title: String?, content: TextOrHtml) = when (content) {
    is TextOrHtml.Text -> BlogBlockPayload(type.name, title = title, text = content.value)
    is TextOrHtml.Html -> BlogBlockPayload(type.name, title = title, html = content.value)
}

private fun contentOf(payload: BlogBlockPayload): TextOrHtml =
    payload.html?.let(TextOrHtml::Html) ?: TextOrHtml.Text(checkNotNull(payload.text) { "A stored ${payload.type} block without its words" })

/** A stored block back; a broken one is a bug and stops loudly. */
internal fun blockOf(payload: BlogBlockPayload): BlogBlock = when (BlogBlockType.valueOf(payload.type)) {
    BlogBlockType.HEADING -> BlogBlock.Heading(checkNotNull(payload.anchor), checkNotNull(payload.text))
    BlogBlockType.PARAGRAPH -> BlogBlock.Paragraph(contentOf(payload))
    BlogBlockType.QUOTE -> BlogBlock.Quote(checkNotNull(payload.text))
    BlogBlockType.TIP -> BlogBlock.Tip(payload.title, contentOf(payload))
    BlogBlockType.PRODUCT -> BlogBlock.ProductCard(ProductId.constructFromPersisted(checkNotNull(payload.productId)))
    BlogBlockType.IMAGE -> BlogBlock.Image(MediaId.constructFromPersisted(checkNotNull(payload.imageMediaId)), payload.text)
    BlogBlockType.LIST -> BlogBlock.ListBlock(checkNotNull(payload.items))
}

internal fun ResultRow.toPost(): BlogPost = BlogPost.constructFromPersisted(
    id = BlogPostId.constructFromPersisted(this[BlogPostTbl.postId]),
    slug = Slug.constructFromPersisted(this[BlogPostTbl.slug]),
    title = this[BlogPostTbl.title],
    excerpt = this[BlogPostTbl.excerpt],
    lead = this[BlogPostTbl.lead],
    categoryId = BlogCategoryId.constructFromPersisted(this[BlogPostTbl.categoryId]),
    authorId = BlogAuthorId.constructFromPersisted(this[BlogPostTbl.authorId]),
    coverMediaId = this[BlogPostTbl.coverMediaId]?.let(MediaId::constructFromPersisted),
    coverBackground = this[BlogPostTbl.coverBackground]?.let(CoverBackground::constructFromPersisted),
    readingMinutes = this[BlogPostTbl.readingMinutes]?.toInt(),
    blocks = this[BlogPostTbl.blocks].map(::blockOf),
    tags = this[BlogPostTbl.tags].map(BlogTag::constructFromPersisted),
    isFeatured = this[BlogPostTbl.isFeatured],
    status = BlogPostStatus.valueOf(this[BlogPostTbl.status]),
    publishedAt = this[BlogPostTbl.publishedAt]?.toInstant(),
    seo = SeoMeta.constructFromPersisted(this[BlogPostTbl.seoTitle], this[BlogPostTbl.seoDescription], this[BlogPostTbl.seoNoIndex]),
    createdOn = this[BlogPostTbl.createdOn].toInstant(),
    createdBy = UserId.constructFromPersisted(this[BlogPostTbl.createdBy]),
    updatedOn = this[BlogPostTbl.updatedOn].toInstant(),
    updatedBy = UserId.constructFromPersisted(this[BlogPostTbl.updatedBy]),
)
