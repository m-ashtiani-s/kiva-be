package com.kiva.application.usecase.blog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.blog.postIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository
import java.time.Clock
import java.time.Instant

/**
 * The panel deletes a post for good (`DELETE /admin/blog/posts/{postId}`; decision 3): its votes go with it (ON DELETE
 * CASCADE); its photos stay attached, so the clean-up never takes a file a cached page may still show. 404 when there is
 * none; one BLOG_POST_DELETED row.
 */
class DeleteBlogPostUc(
    private val transactionManager: TransactionManager,
    private val blogPostRepository: BlogPostRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteBlogPostUc.Cmd, Unit, DeleteBlogPostUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val postId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteBlogPostUc.execute post=${command.postId.take(20)} by=${command.actor.userId.value}")
        val id = postIdOf(command.postId) ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            val post = blogPostRepository.lock(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            blogPostRepository.delete(id)
            val now: Instant = clock.instant()
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.BLOG_POST_DELETED, AuditEntityType.BLOG_POST, id.value.toString(), post.title, null))
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
