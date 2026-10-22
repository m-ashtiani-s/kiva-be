package com.kiva.application.usecase.review.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import java.time.Clock

/**
 * Kiva's reply taken away (`DELETE /admin/reviews/{reviewId}/reply`; ADMIN, SUPPORT; roadmap section 13.2, decision 12):
 * always 204, with no reply too; a review nobody has is 404. A REVIEW_REPLY_DELETED row in the change log only when a
 * reply really went.
 */
class DeleteReviewReplyUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteReviewReplyUc.Cmd, Unit, DeleteReviewReplyUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val reviewId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReviewId.makeNew(command.reviewId).getOrNull()
        logger.info("DeleteReviewReplyUc.execute reviewId=${id?.value ?: "(not an id)"} actor=${command.actor.userId.value}")
        if (id == null) return Result.failure(Failure.ReviewNotFound)
        return transactionManager.inTransaction {
            val review = reviewRepository.lock(id) ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            val now = clock.instant()
            if (!review.removeReply(now)) return@inTransaction Result.success(Unit)
            reviewRepository.save(review)
            val label = productQmRepository.findBriefs(listOf(review.productId))[review.productId]?.name?.value
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.REVIEW_REPLY_DELETED, AuditEntityType.REVIEW, id.value.toString(), label, null))
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
