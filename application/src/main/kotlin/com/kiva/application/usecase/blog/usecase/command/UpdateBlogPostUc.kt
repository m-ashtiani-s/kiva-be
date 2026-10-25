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
import com.kiva.application.usecase.blog.postIdOf
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import java.time.Clock

/**
 * The panel rewrites a post (`PUT /admin/blog/posts/{postId}`; roadmap section 20.6): the same checks as making one, under
 * the post's lock (404 when there is none), the slug nobody else's but its own; the whole post replaced (decision 3's
 * date rule for publishing again, decision 19). One BLOG_POST_UPDATED row when something changed.
 */
class UpdateBlogPostUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val blogPostWriting: BlogPostWriting,
    private val blogPosts: BlogPosts,
    private val mediaAttachments: MediaAttachments,
    private val sanitizer: HtmlSanitizer,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateBlogPostUc.Cmd, BlogPostId, SaveBlogPostFailure> {

    private val logger = getLogger()

    /** @property postId the path's `{postId}` as it came. */
    data class Cmd(val postId: String, val fields: BlogPostDraft.Fields, val publishedAt: String?, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<BlogPostId, SaveBlogPostFailure> {
        logger.info("UpdateBlogPostUc.execute post=${command.postId.take(20)} by=${command.actor.userId.value}")
        val id = postIdOf(command.postId) ?: return Result.failure(SaveBlogPostFailure.NotFound)
        val draft = draftOf(command.fields, command.publishedAt, sanitizer).unwrapOr { return Result.failure(SaveBlogPostFailure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val before = blogPostRepository.lock(id) ?: return@inTransaction Result.failure(SaveBlogPostFailure.NotFound)
            val checked = blogPostWriting.check(draft).unwrapOr { return@inTransaction Result.failure(SaveBlogPostFailure.InvalidInput(it.causes)) }
            if (blogPostRepository.isSlugTaken(draft.slug, except = id)) return@inTransaction Result.failure(SaveBlogPostFailure.DuplicateSlug)
            val now = clock.instant()
            val after = before.rewrite(draft, checked.authorId, command.actor.userId, now)
            val changes = BlogPostWriting.changesOf(before, after)
            if (changes.isEmpty()) {
                logger.info("UpdateBlogPostUc: post ${id.value} unchanged")
                return@inTransaction Result.success(id)
            }
            if (after.isFeatured) blogPostRepository.unfeatureAllExcept(id)
            try {
                blogPostRepository.save(after, blogPosts.searchTextOf(after, checked.categoryName))
            } catch (e: UniqueTakenException) {
                logger.info("UpdateBlogPostUc: ${e.message} between the check and the update")
                return@inTransaction Result.failure(SaveBlogPostFailure.DuplicateSlug)
            }
            mediaAttachments.attach(after.mediaIds(), now)
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.BLOG_POST_UPDATED, AuditEntityType.BLOG_POST, id.value.toString(), after.title, changes))
            logger.info("UpdateBlogPostUc: post ${id.value} ${after.status}")
            Result.success(id)
        }
    }
}
