package com.kiva.application.usecase.blog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.blog.BlogPostWriting
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.application.usecase.blog.SaveBlogPostFailure
import com.kiva.application.usecase.blog.draftOf
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import java.time.Clock

/**
 * The panel writes a new post (`POST /admin/blog/posts`; ADMIN, CONTENT_EDITOR; roadmap section 20.6): the form (400,
 * the HTML sanitized first), then in one transaction the category, author, photos and products (400), the slug nobody
 * else's (409 DUPLICATE_SLUG, also when another post took it at the same moment), the featured one unfeatured first
 * (decision 5), the post stored, its photos attached, and one BLOG_POST_CREATED row. Returns its id; the route answers
 * with GetAdminBlogPostUc.
 */
class CreateBlogPostUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogPostWriting: BlogPostWriting,
    private val blogPosts: BlogPosts,
    private val mediaAttachments: MediaAttachments,
    private val sanitizer: HtmlSanitizer,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateBlogPostUc.Cmd, BlogPostId, SaveBlogPostFailure> {

    private val logger = getLogger()

    data class Cmd(val fields: BlogPostDraft.Fields, val publishedAt: String?, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<BlogPostId, SaveBlogPostFailure> {
        logger.info("CreateBlogPostUc.execute slug=${command.fields.slug?.take(100)} by=${command.actor.userId.value}")
        val draft = draftOf(command.fields, command.publishedAt, sanitizer).unwrapOr { return Result.failure(SaveBlogPostFailure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val checked = blogPostWriting.check(draft).unwrapOr { return@inTransaction Result.failure(SaveBlogPostFailure.InvalidInput(it.causes)) }
            if (blogPostRepository.isSlugTaken(draft.slug, except = null)) return@inTransaction Result.failure(SaveBlogPostFailure.DuplicateSlug)
            val now = clock.instant()
            val post = BlogPost.create(blogPostRepository.nextId(), draft, checked.authorId, command.actor.userId, now)
            if (post.isFeatured) blogPostRepository.unfeatureAllExcept(post.id)
            try {
                blogPostRepository.add(post, blogPosts.searchTextOf(post, checked.categoryName))
            } catch (e: UniqueTakenException) {
                logger.info("CreateBlogPostUc: ${e.message} between the check and the insert")
                return@inTransaction Result.failure(SaveBlogPostFailure.DuplicateSlug)
            }
            mediaAttachments.attach(post.mediaIds(), now)
            auditTrail.record(
                AuditEntry(now, command.actor, AuditAction.BLOG_POST_CREATED, AuditEntityType.BLOG_POST, post.id.value.toString(), post.title, BlogPostWriting.changesOf(null, post)),
            )
            logger.info("CreateBlogPostUc: post ${post.id.value} ${post.status}")
            Result.success(post.id)
        }
    }
}
