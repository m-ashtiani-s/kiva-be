package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.repository.OrderQmRepository
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
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeTarget
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Clock

/**
 * The customer's answer to the photos of their bag (`POST /me/orders/{orderCode}/media-feedback`). The customer's own
 * order only (404 otherwise); nothing in the change log, which is the panel's.
 *
 * «همونه!» (APPROVE) lets the order ship without waiting for the deadline; approving photos approved already is fine and
 * changes nothing. Only while photos sent wait for an answer, otherwise 422 INVALID_STATUS_TRANSITION. The approval and
 * the job approving the same photos at once: the second is refused at the order's stream, and runs again to find them
 * approved already.
 *
 * A change request (REQUEST_CHANGE, phase 3, roadmap section 8.5), in this order:
 * 1. the shape, before any transaction, every problem at once (400): the change's type, and what it needs (decision 2 of
 *    section 8.2: COLOR and MODEL a line and a color, CANCEL_ITEM a line, OTHER a note), the note at most 500;
 * 2. [tx] the customer's own order (404), its row locked (section 4.11, lock 9), so staff resolving or cancelling it at
 *    the same moment go one after the other;
 * 3. the color asked for, from the read model: none such is 400 DESIRED_VARIANT_INVALID, one switched off or of a product
 *    not in the shop is 422 PRODUCT_UNAVAILABLE (its stock is staff's to check when they carry it out);
 * 4. the order's rules (Order.requestChange): only after photos the customer saw and before it ships, one at a time (422
 *    CHANGE_REQUEST_NOT_ALLOWED), a line it has (400 ORDER_ITEM_NOT_FOUND), a color that fits the type (400
 *    DESIRED_VARIANT_INVALID).
 */
class SubmitMediaFeedbackUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val offerQmRepository: OfferQmRepository,
    private val clock: Clock,
) : CommandUseCase<SubmitMediaFeedbackUc.Cmd, Unit, SubmitMediaFeedbackUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [note] is the customer's, never logged ([toString]). */
    data class Cmd(
        val userId: UserId,
        val orderCode: String,
        val decision: String?,
        val changeType: String? = null,
        val orderItemId: Int? = null,
        val desiredVariantId: Long? = null,
        val note: String? = null,
    ) {
        override fun toString(): String =
            "Cmd(userId=${userId.value}, orderCode=$orderCode, decision=$decision, changeType=$changeType, orderItemId=$orderItemId, desiredVariantId=$desiredVariantId)"
    }

    /** What the customer answered, its shape checked. */
    private sealed interface Answer {
        data object Approve : Answer

        data class Change(val request: ChangeRequest) : Answer
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("SubmitMediaFeedbackUc.execute userId=${command.userId.value} orderCode=${code?.value ?: "(not a code)"} decision=${command.decision} changeType=${command.changeType}")
        val answer = when (val checked = answerOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (code == null) return Result.failure(Failure.OrderNotFound)

        repeat(MAX_ATTEMPTS - 1) {
            try {
                return answerOnce(command, answer)
            } catch (e: ConcurrentChangeException) {
                logger.info("SubmitMediaFeedbackUc: order ${code.value} changed meanwhile; trying again")
            }
        }
        return answerOnce(command, answer)
    }

    private suspend fun answerOnce(command: Cmd, answer: Answer): Result<Unit, Failure> = transactionManager.inTransaction {
        when (answer) {
            Answer.Approve -> {
                val order = orderByCode.loadOfCustomer(command.userId, command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
                when (val approved = order.approvePhotos(automatic = false, command.userId, clock.instant())) {
                    is Result.Success -> orderRepository.save(order)
                    is Result.Failure -> return@inTransaction Result.failure(failureOf(approved.failure))
                }
            }
            is Answer.Change -> {
                val read = orderByCode.findOfCustomer(command.userId, command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
                orderQmRepository.lockOrder(read.orderId) ?: error("Order ${read.orderId.value} was read a moment ago, yet it isn't there")
                val request = answer.request
                val target = request.desiredVariantId?.let { wanted ->
                    val offer = offerQmRepository.findOffers(listOf(wanted))[wanted]
                        ?: return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("desiredVariantId", Problem.DesiredVariantInvalid))))
                    if (!offer.offer.inShop) return@inTransaction Result.failure(Failure.ProductUnavailable)
                    ChangeTarget(wanted, offer.offer.productId)
                }
                val order = orderRepository.get(read.orderId) ?: error("Order ${read.orderId.value} of the read model has no events")
                when (val asked = order.requestChange(request, target, command.userId, clock.instant())) {
                    is Result.Success -> orderRepository.save(order)
                    is Result.Failure -> return@inTransaction Result.failure(failureOf(asked.failure))
                }
                logger.info("SubmitMediaFeedbackUc: order ${order.code.value} has a ${request.type} change request open")
            }
        }
        Result.success(Unit)
    }

    /** The shape of the answer, every problem at once (400); APPROVE needs nothing else, and what it doesn't need is ignored. */
    private fun answerOf(command: Cmd): Result<Answer, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val decision = FeedbackDecision.entries.firstOrNull { it.name == command.decision?.trim() }
            ?: null.also { problems += FieldFailure("decision", Problem.DecisionInvalid) }
        if (decision != FeedbackDecision.REQUEST_CHANGE) {
            return if (problems.isEmpty()) Result.success(Answer.Approve) else Result.failure(Failure.InvalidInput(problems))
        }

        val type = when (val raw = command.changeType?.trim()?.ifEmpty { null }) {
            null -> null.also { problems += FieldFailure("changeType", Problem.ChangeTypeRequired) }
            else -> when (val parsed = ChangeType.makeNew(raw)) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }
        val note = when (val parsed = StatusNote.makeNew(command.note)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val lineId = command.orderItemId.takeIf { type?.hasLine == true }
        val desired = command.desiredVariantId.takeIf { type?.swapsColor == true }
        var variantId: VariantId? = null
        if (type != null) {
            if (type.hasLine) {
                when {
                    lineId == null -> problems += FieldFailure("orderItemId", Problem.OrderItemRequired)
                    lineId < 1 -> problems += FieldFailure("orderItemId", Problem.OrderItemNotFound)
                }
            }
            if (type.swapsColor) {
                when (desired) {
                    null -> problems += FieldFailure("desiredVariantId", Problem.DesiredVariantRequired)
                    else -> variantId = VariantId.makeNew(desired).getOrNull() ?: null.also { problems += FieldFailure("desiredVariantId", Problem.DesiredVariantInvalid) }
                }
            }
            if (type == ChangeType.OTHER && note == null && problems.none { it.field == "note" }) problems += FieldFailure("note", Problem.NoteRequired)
        }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        return Result.success(Answer.Change(ChangeRequest(checkNotNull(type), lineId, variantId, note)))
    }

    private fun failureOf(failure: Order.ApprovePhotosFailure): Failure = when (failure) {
        is Order.ApprovePhotosFailure.NotAwaitingApproval -> Failure.NotAwaitingApproval(failure.meta)
        // The customer's approval never waits for the deadline.
        is Order.ApprovePhotosFailure.NotDue -> error("The customer's approval is never early")
    }

    private fun failureOf(failure: Order.RequestChangeFailure): Failure = when (failure) {
        is Order.RequestChangeFailure.NotAllowed -> Failure.ChangeRequestNotAllowed(failure.meta)
        is Order.RequestChangeFailure.UnknownLine, Order.RequestChangeFailure.DesiredVariantInvalid -> Failure.InvalidInput(listOf(failure))
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object DecisionInvalid : Problem("FEEDBACK_DECISION_INVALID")

        data object ChangeTypeRequired : Problem("CHANGE_TYPE_REQUIRED")

        data object OrderItemRequired : Problem("ORDER_ITEM_REQUIRED")

        data object OrderItemNotFound : Problem("ORDER_ITEM_NOT_FOUND")

        data object DesiredVariantRequired : Problem("DESIRED_VARIANT_REQUIRED")

        data object DesiredVariantInvalid : Problem("DESIRED_VARIANT_INVALID")

        data object NoteRequired : Problem("NOTE_REQUIRED")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** No photos sent wait for an answer (422). */
        data class NotAwaitingApproval(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)

        /** Not after photos the customer saw, one open already, or it shipped (422). */
        data class ChangeRequestNotAllowed(val details: Map<String, Any>) : Failure("CHANGE_REQUEST_NOT_ALLOWED", FailureKind.BUSINESS_RULE, meta = details)

        /** The color asked for is switched off, or its product isn't in the shop (422). */
        data object ProductUnavailable : Failure("PRODUCT_UNAVAILABLE", FailureKind.BUSINESS_RULE)
    }
}
