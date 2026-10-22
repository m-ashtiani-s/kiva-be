package com.kiva.application.usecase.review.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.review.RatingBook
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import java.time.Clock

/**
 * Support approves or turns down a review (`POST /admin/reviews/{reviewId}/moderation`; ADMIN, SUPPORT; roadmap section
 * 13.2, decisions 10 and 11). PENDING to APPROVED or REJECTED, APPROVED to REJECTED, REJECTED to APPROVED; the same
 * again is 422 INVALID_STATUS_TRANSITION. Turning down needs a reason of 3 to 300 (400 REJECTION_REASON_REQUIRED), only
 * its author reads it; Kiva's reply (2 to 1000) goes only with an approval (400 REPLY_REQUIRES_APPROVAL with a
 * rejection).
 *
 * The shape first, every problem at once. Under the review's row lock (lock 11) and, when its place in the rating
 * changes, the product's rating's row right after (RatingBook). One REVIEW_MODERATED row in the change log (the status,
 * and that a reply was written, never its text). No SMS. A panel form: never tried again.
 */
class ModerateReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val ratingBook: RatingBook,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<ModerateReviewUc.Cmd, Unit, ModerateReviewUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; staff's texts never go into a log ([toString]). */
    data class Cmd(val reviewId: String, val action: String?, val rejectionReason: String?, val reply: String?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(reviewId=$reviewId, action=$action, reply=${reply != null}, actor=${actor.userId.value})"
    }

    private sealed class Decision {
        data class Approve(val reply: ReviewText?) : Decision()
        data class Reject(val reason: ReviewText) : Decision()
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReviewId.makeNew(command.reviewId).getOrNull()
        logger.info("ModerateReviewUc.execute reviewId=${id?.value ?: "(not an id)"} action=${command.action} actor=${command.actor.userId.value}")
        val decision = when (val checked = decisionOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (id == null) return Result.failure(Failure.ReviewNotFound)

        return transactionManager.inTransaction {
            val review = reviewRepository.lock(id) ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            val now = clock.instant()
            val before = RatingBook.Counted.of(review)
            val statusBefore = review.status
            val moved = when (decision) {
                is Decision.Approve -> review.approve(decision.reply, command.actor.userId, now)
                is Decision.Reject -> review.reject(decision.reason, command.actor.userId, now)
            }
            if (moved is Result.Failure) return@inTransaction Result.failure(Failure.InvalidTransition(moved.failure.meta))
            ratingBook.changed(before, review, now)
            try {
                reviewRepository.save(review)
            } catch (e: UniqueTakenException) {
                // A rejected review approved while its author has another live one of the product: one live at a time.
                logger.info("ModerateReviewUc: ${e.message}")
                return@inTransaction Result.failure(Failure.AnotherLive)
            }
            val label = productQmRepository.findBriefs(listOf(review.productId))[review.productId]?.name?.value
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.REVIEW_MODERATED, AuditEntityType.REVIEW, id.value.toString(), label,
                    AuditDiff.build {
                        field("status", statusBefore, review.status)
                        if (decision is Decision.Approve && decision.reply != null) longText("reply", null, decision.reply.value)
                    },
                ),
            )
            logger.info("ModerateReviewUc: review ${id.value} of product ${review.productId.value} is ${review.status}")
            Result.success(Unit)
        }
    }

    /** The body's shape, every problem at once: only the fields of the action taken are read, a reply with a rejection refused. */
    private fun decisionOf(command: Cmd): Result<Decision, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val decision = when (command.action?.trim()?.ifEmpty { null }) {
            null -> null.also { problems += FieldFailure("action", FieldRequired) }
            APPROVE -> {
                val reply = command.reply?.takeIf { it.isNotBlank() }?.let { raw ->
                    when (val made = ReviewText.makeNew(raw, ReviewText.MIN_REPLY, ReviewText.MAX_LENGTH, FieldRequired).forField("reply")) {
                        is Result.Success -> made.value
                        is Result.Failure -> null.also { problems += made.failure }
                    }
                }
                Decision.Approve(reply)
            }
            REJECT -> {
                if (!command.reply.isNullOrBlank()) problems += FieldFailure("reply", Problem.ReplyRequiresApproval)
                val made = ReviewText.makeNew(command.rejectionReason, ReviewText.MIN_REASON, ReviewText.MAX_REASON, Problem.ReasonRequired, Problem.ReasonRequired)
                    .forField("rejectionReason")
                when (made) {
                    is Result.Success -> Decision.Reject(made.value)
                    is Result.Failure -> null.also { problems += made.failure }
                }
            }
            else -> null.also { problems += FieldFailure("action", Problem.ActionInvalid) }
        }
        return if (problems.isEmpty()) Result.success(checkNotNull(decision)) else Result.failure(Failure.InvalidInput(problems))
    }

    private companion object {
        const val APPROVE = "APPROVE"
        const val REJECT = "REJECT"
    }

    /** What can be wrong with the body; the field is added where it is found. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object ActionInvalid : Problem("MODERATION_ACTION_INVALID")
        data object ReasonRequired : Problem("REJECTION_REASON_REQUIRED")
        data object ReplyRequiresApproval : Problem("REPLY_REQUIRES_APPROVAL")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The same status again (422), with `meta.from`, `to` and `allowed`. */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)

        /** Its author has another live review of the product: approving this one would make two (409). */
        data object AnotherLive : Failure("REVIEW_ALREADY_EXISTS", FailureKind.CONFLICT)
    }
}
