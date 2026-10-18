package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.repository.OrderRepository
import java.time.Clock

/**
 * The customer's answer to the photos of their bag (`POST /me/orders/{orderCode}/media-feedback`). «همونه!» (APPROVE)
 * lets the order ship without waiting for the deadline; approving photos approved already is fine and changes nothing.
 * Only while photos sent wait for an answer, otherwise 422 INVALID_STATUS_TRANSITION. A change request (REQUEST_CHANGE)
 * is phase 3: until then 422 CHANGE_REQUEST_NOT_ALLOWED. The customer's own order only (404 otherwise); nothing in the
 * change log, which is the panel's.
 *
 * The approval and the job approving the same photos at once: the second is refused at the order's stream, and runs
 * again to find them approved already.
 */
class SubmitMediaFeedbackUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderRepository: OrderRepository,
    private val clock: Clock,
) : CommandUseCase<SubmitMediaFeedbackUc.Cmd, Unit, SubmitMediaFeedbackUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [note] and the change's fields are phase 3's. */
    data class Cmd(val userId: UserId, val orderCode: String, val decision: String?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("SubmitMediaFeedbackUc.execute userId=${command.userId.value} orderCode=${code?.value ?: "(not a code)"} decision=${command.decision}")
        val decision = FeedbackDecision.entries.firstOrNull { it.name == command.decision?.trim() }
            ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("decision", Problem.DecisionInvalid))))
        if (code == null) return Result.failure(Failure.OrderNotFound)

        repeat(MAX_ATTEMPTS - 1) {
            try {
                return answerOnce(command, decision)
            } catch (e: ConcurrentChangeException) {
                logger.info("SubmitMediaFeedbackUc: order ${code.value} changed meanwhile; trying again")
            }
        }
        return answerOnce(command, decision)
    }

    private suspend fun answerOnce(command: Cmd, decision: FeedbackDecision): Result<Unit, Failure> = transactionManager.inTransaction {
        val order = orderByCode.loadOfCustomer(command.userId, command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
        when (decision) {
            FeedbackDecision.REQUEST_CHANGE -> return@inTransaction Result.failure(Failure.ChangeRequestNotAllowed)
            FeedbackDecision.APPROVE -> when (val approved = order.approvePhotos(automatic = false, command.userId, clock.instant())) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(failureOf(approved.failure))
            }
        }
        orderRepository.save(order)
        Result.success(Unit)
    }

    private fun failureOf(failure: Order.ApprovePhotosFailure): Failure = when (failure) {
        is Order.ApprovePhotosFailure.NotAwaitingApproval -> Failure.NotAwaitingApproval(failure.meta)
        // The customer's approval never waits for the deadline.
        is Order.ApprovePhotosFailure.NotDue -> error("The customer's approval is never early")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object DecisionInvalid : Problem("FEEDBACK_DECISION_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** No photos sent wait for an answer (422). */
        data class NotAwaitingApproval(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)

        /** A change request comes with phase 3 (422). */
        data object ChangeRequestNotAllowed : Failure("CHANGE_REQUEST_NOT_ALLOWED", FailureKind.BUSINESS_RULE)
    }
}
