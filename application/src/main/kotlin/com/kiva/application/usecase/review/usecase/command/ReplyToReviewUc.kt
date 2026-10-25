package com.kiva.application.usecase.review.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import java.time.Clock

/**
 * «پاسخ کیوا», written or rewritten (`PUT /admin/reviews/{reviewId}/reply`; ADMIN, SUPPORT; roadmap section 13.2,
 * decision 12): plain text of 2 to 1000, only under an approved review (422 INVALID_STATUS_TRANSITION otherwise). Under
 * the review's row lock. One REVIEW_REPLIED row in the change log, without the text.
 */
class ReplyToReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<ReplyToReviewUc.Cmd, Unit, ReplyToReviewUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; the reply never goes into a log ([toString]). */
    data class Cmd(val reviewId: String, val text: String?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(reviewId=$reviewId, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReviewId.makeNew(command.reviewId).getOrNull()
        logger.info("ReplyToReviewUc.execute reviewId=${id?.value ?: "(not an id)"} actor=${command.actor.userId.value}")
        val text = when (val made = ReviewText.makeNew(command.text, ReviewText.MIN_REPLY, ReviewText.MAX_LENGTH, FieldRequired).forField("text")) {
            is Result.Success -> made.value
            is Result.Failure -> return Result.failure(Failure.InvalidInput(listOf(made.failure)))
        }
        if (id == null) return Result.failure(Failure.ReviewNotFound)

        return transactionManager.inTransaction {
            val review = reviewRepository.lock(id) ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            val now = clock.instant()
            val before = review.reply?.text
            when (val replied = review.reply(text, command.actor.userId, now)) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(Failure.InvalidTransition(replied.failure.meta))
            }
            reviewRepository.save(review)
            val label = productQmRepository.findBriefs(listOf(review.productId))[review.productId]?.name?.value
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.REVIEW_REPLIED, AuditEntityType.REVIEW, id.value.toString(), label,
                    AuditDiff.build { longText("reply", before?.value, text.value) },
                ),
            )
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not approved (422). */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)
    }
}
