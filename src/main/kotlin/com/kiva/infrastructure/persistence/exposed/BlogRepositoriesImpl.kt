package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.entity.BlogAuthor
import com.kiva.domain.aggregate.blog.entity.BlogCategory
import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.repository.BlogAuthorRepository
import com.kiva.domain.aggregate.blog.repository.BlogCategoryRepository
import com.kiva.domain.aggregate.blog.repository.BlogFeedbackRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.infrastructure.persistence.exposed.tbl.BlogAuthorTbl
import com.kiva.infrastructure.persistence.exposed.tbl.BlogCategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.BlogFeedbackTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class BlogCategoryRepositoryImpl : BlogCategoryRepository {
    override fun findAll(): List<BlogCategory> =
        BlogCategoryTbl.selectAll().orderBy(BlogCategoryTbl.sortOrder to SortOrder.ASC, BlogCategoryTbl.id to SortOrder.ASC).map { it.toCategory() }

    override fun find(id: BlogCategoryId): BlogCategory? = BlogCategoryTbl.selectAll().where { BlogCategoryTbl.id eq id.value }.singleOrNull()?.toCategory()

    override fun findBySlug(slug: Slug): BlogCategory? = BlogCategoryTbl.selectAll().where { BlogCategoryTbl.slug eq slug.value }.singleOrNull()?.toCategory()

    private fun ResultRow.toCategory() = BlogCategory.constructFromPersisted(
        BlogCategoryId.constructFromPersisted(this[BlogCategoryTbl.id].value),
        Slug.constructFromPersisted(this[BlogCategoryTbl.slug]),
        this[BlogCategoryTbl.name],
        this[BlogCategoryTbl.sortOrder],
    )
}

/** Runs inside the use case's transaction; it never opens one itself. */
class BlogAuthorRepositoryImpl : BlogAuthorRepository {
    override fun find(id: BlogAuthorId): BlogAuthor? = BlogAuthorTbl.selectAll().where { BlogAuthorTbl.id eq id.value }.singleOrNull()?.toAuthor()

    override fun findDefault(): BlogAuthor? = BlogAuthorTbl.selectAll().where { BlogAuthorTbl.isDefault eq true }.singleOrNull()?.toAuthor()

    override fun findAll(ids: Collection<BlogAuthorId>): Map<BlogAuthorId, BlogAuthor> {
        if (ids.isEmpty()) return emptyMap()
        return BlogAuthorTbl.selectAll().where { BlogAuthorTbl.id inList ids.map { it.value } }.map { it.toAuthor() }.associateBy { it.id }
    }

    private fun ResultRow.toAuthor() = BlogAuthor.constructFromPersisted(
        BlogAuthorId.constructFromPersisted(this[BlogAuthorTbl.id].value),
        this[BlogAuthorTbl.name],
        this[BlogAuthorTbl.title],
        this[BlogAuthorTbl.bio],
        this[BlogAuthorTbl.avatarMediaId]?.let(MediaId::constructFromPersisted),
        this[BlogAuthorTbl.isDefault],
    )
}

/** Runs inside the use case's transaction; it never opens one itself. */
class BlogFeedbackRepositoryImpl : BlogFeedbackRepository {

    /** `INSERT … ON CONFLICT (postId, voterKey) DO UPDATE`: two votes of one voter at once leave one row, the later one. */
    override fun upsert(postId: BlogPostId, voterKey: String, helpful: Boolean, comment: String?, now: Instant) {
        BlogFeedbackTbl.upsert {
            it[BlogFeedbackTbl.postId] = postId.value
            it[BlogFeedbackTbl.voterKey] = voterKey
            it[BlogFeedbackTbl.helpful] = helpful
            it[BlogFeedbackTbl.comment] = comment
            it[createdOn] = now.atOffset(ZoneOffset.UTC)
        }
    }

    override fun statsOf(postId: BlogPostId): BlogFeedbackRepository.Stats {
        val count = BlogFeedbackTbl.voterKey.count()
        val byAnswer = BlogFeedbackTbl.select(BlogFeedbackTbl.helpful, count)
            .where { BlogFeedbackTbl.postId eq postId.value }
            .groupBy(BlogFeedbackTbl.helpful)
            .associate { it[BlogFeedbackTbl.helpful] to it[count].toInt() }
        return BlogFeedbackRepository.Stats(byAnswer[true] ?: 0, byAnswer[false] ?: 0)
    }

    override fun lastComments(postId: BlogPostId, limit: Int): List<BlogFeedbackRepository.Comment> =
        BlogFeedbackTbl.select(BlogFeedbackTbl.comment, BlogFeedbackTbl.createdOn)
            .where { (BlogFeedbackTbl.postId eq postId.value) and BlogFeedbackTbl.comment.isNotNull() }
            .orderBy(BlogFeedbackTbl.createdOn to SortOrder.DESC)
            .limit(limit)
            .map { BlogFeedbackRepository.Comment(it[BlogFeedbackTbl.comment]!!, it[BlogFeedbackTbl.createdOn].toInstant()) }

    override fun deleteVotesOfUser(userId: UserId): Int = BlogFeedbackTbl.deleteWhere { BlogFeedbackTbl.voterKey eq "u:${userId.value}" }
}
