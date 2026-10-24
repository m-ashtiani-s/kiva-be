package com.kiva.domain.aggregate.blog.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.entity.BlogAuthor
import com.kiva.domain.aggregate.blog.entity.BlogCategory
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import com.kiva.domain.aggregate.blog.model.valueobject.BlogTag
import java.time.Instant

/**
 * The blog's posts (roadmap phase 15, section 20.3). Runs inside the use case's transaction. [searchText] is what `q`
 * looks in (the title, excerpt, tags and the category's name, normalized), written with the post.
 */
interface BlogPostRepository {
    fun nextId(): BlogPostId

    /** Stores a new post; its slug taken meanwhile by another is UniqueTakenException (the unique key has the last word). */
    fun add(post: BlogPost, searchText: String)

    /** Writes back a post read with [lock]; a slug taken meanwhile is UniqueTakenException. */
    fun save(post: BlogPost, searchText: String)

    fun find(id: BlogPostId): BlogPost?

    /** The post, its row held until the transaction ends. */
    fun lock(id: BlogPostId): BlogPost?

    fun findBySlug(slug: Slug): BlogPost?

    /** Another post than [except] has [slug]. */
    fun isSlugTaken(slug: Slug, except: BlogPostId?): Boolean

    fun delete(id: BlogPostId)

    /**
     * Every post but [id] stops being featured (decision 5); before [id] is saved featured, so the one-featured index
     * never sees two.
     */
    fun unfeatureAllExcept(id: BlogPostId)

    /** What the shop sees at [now] that matches [filter], the latest published first (then the higher id). */
    fun pagePublic(filter: PublicFilter, now: Instant, pageable: Pageable): Page<BlogPost>

    /** The featured post, if the shop sees it at [now] and it matches [filter]. */
    fun featured(filter: PublicFilter, now: Instant): BlogPost?

    /** At most [limit] posts the shop sees at [now] besides [post]: those of its category first, each part the latest first. */
    fun related(post: BlogPost, now: Instant, limit: Int): List<BlogPost>

    /** The panel's list: every status, the latest change first. */
    fun pageAdmin(filter: AdminFilter, pageable: Pageable): Page<BlogPost>

    /** How many posts of each category the shop sees at [now]; a category with none is missing. */
    fun countVisibleByCategory(now: Instant): Map<BlogCategoryId, Int>

    /**
     * @property words each one must be in the post's search text (normalized).
     * @property exclude a post left out (the featured one on page one).
     */
    data class PublicFilter(val categoryId: BlogCategoryId?, val words: List<String>, val tag: BlogTag?, val exclude: BlogPostId? = null)

    data class AdminFilter(val status: BlogPostStatus?, val words: List<String>, val categoryId: BlogCategoryId?)
}

/** The blog's categories, made with SQL (decision 1). */
interface BlogCategoryRepository {
    /** Every category, in the order the shop shows them. */
    fun findAll(): List<BlogCategory>

    fun find(id: BlogCategoryId): BlogCategory?

    fun findBySlug(slug: Slug): BlogCategory?
}

/** The blog's authors, made with SQL (decision 1). */
interface BlogAuthorRepository {
    fun find(id: BlogAuthorId): BlogAuthor?

    /** The author of a post whose form names none. */
    fun findDefault(): BlogAuthor?

    fun findAll(ids: Collection<BlogAuthorId>): Map<BlogAuthorId, BlogAuthor>
}

/**
 * «این مطلب مفید بود؟» (decisions 14, 15, 18): one vote per post and voter, a voter being `u:{userId}` or `ip:` and a
 * peppered hash. The IP itself never reaches here.
 */
interface BlogFeedbackRepository {
    /** The vote of [voterKey] on [postId], replacing an earlier one; [comment] only with «no». */
    fun upsert(postId: BlogPostId, voterKey: String, helpful: Boolean, comment: String?, now: Instant)

    fun statsOf(postId: BlogPostId): Stats

    /** The last [limit] comments of «no», newest first, without who wrote them. */
    fun lastComments(postId: BlogPostId, limit: Int): List<Comment>

    /** Removes the votes of [userId] (deleting the account, AccountEraser); how many went. */
    fun deleteVotesOfUser(userId: UserId): Int

    data class Stats(val helpful: Int, val notHelpful: Int)

    data class Comment(val text: String, val createdOn: Instant) {
        override fun toString(): String = "Comment(createdOn=$createdOn)"
    }
}
