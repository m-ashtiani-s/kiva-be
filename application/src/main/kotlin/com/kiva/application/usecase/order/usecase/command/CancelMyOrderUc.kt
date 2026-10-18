package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.dto.RefundDto
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderCancellation
import com.kiva.application.usecase.order.OrderNotices
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * The customer cancels their own order (`POST /me/orders/{orderCode}/cancel`), only while its status hasn't moved: held
 * or being prepared, and no photo ever sent (422 ORDER_NOT_CANCELLABLE otherwise, a second time too). Everything goes
 * with it (OrderCancellation), and the answer has the refund it opened (null when nothing was paid). «لغو شد» after the
 * commit, always. Nothing in the change log: it is the customer's, not the panel's.
 *
 * `reason` is one of the contract's; `note` is required for OTHER (400 NOTE_REQUIRED). The customer and an operator
 * cancelling at once: the second runs again and finds it cancelled.
 */
class CancelMyOrderUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderCancellation: OrderCancellation,
    private val userRepository: UserRepository,
    private val orderNotices: OrderNotices,
    private val clock: Clock,
) : CommandUseCase<CancelMyOrderUc.Cmd, CancelMyOrderUc.Cancelled, CancelMyOrderUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [note] never goes into a log ([toString]). */
    data class Cmd(val userId: UserId, val orderCode: String, val reason: String?, val note: String?) {
        override fun toString(): String = "Cmd(userId=${userId.value}, orderCode=$orderCode, reason=$reason)"
    }

    /** The refund opened (null when nothing was paid). */
    data class Cancelled(val refund: RefundDto?)

    private data class Input(val reason: CancelReason, val note: StatusNote?)

    private data class Done(val refund: RefundDto?, val code: OrderCode, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<Cancelled, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("CancelMyOrderUc.execute userId=${command.userId.value} orderCode=${code?.value ?: "(not a code)"} reason=${command.reason}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (code == null) return Result.failure(Failure.OrderNotFound)
        val checked = (input as Result.Success).value

        repeat(MAX_ATTEMPTS - 1) {
            try {
                return cancelOnce(command, checked)
            } catch (e: ConcurrentChangeException) {
                logger.info("CancelMyOrderUc: order ${code.value} changed meanwhile; trying again")
            }
        }
        return cancelOnce(command, checked)
    }

    private suspend fun cancelOnce(command: Cmd, input: Input): Result<Cancelled, Failure> {
        val done = transactionManager.inTransaction {
            val read = orderByCode.findOfCustomer(command.userId, command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            when (val cancelled = orderCancellation.run(read.orderId, CancelledBy.CUSTOMER, input.reason, input.note, command.userId, clock.instant())) {
                is Result.Success -> Result.success(Done(cancelled.value.refund, read.code, userRepository.findById(read.userId)?.phone))
                is Result.Failure -> Result.failure(Failure.Refused(cancelled.failure.code, cancelled.failure.meta))
            }
        }
        return when (done) {
            is Result.Success -> {
                orderNotices.cancelled(done.value.to, done.value.code)
                Result.success(Cancelled(done.value.refund))
            }
            is Result.Failure -> done
        }
    }

    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val reason = when (val parsed = CancelReason.makeNew(command.reason?.trim().orEmpty())) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val note = when (val parsed = StatusNote.makeNew(command.note)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        if (reason == CancelReason.OTHER && note == null && problems.none { it.field == "note" }) problems += FieldFailure("note", Problem.NoteRequired)
        return if (problems.isEmpty()) Result.success(Input(reason!!, note)) else Result.failure(Failure.InvalidInput(problems))
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object NoteRequired : Problem("NOTE_REQUIRED")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** ORDER_NOT_CANCELLABLE (its status moved, or cancelled already) or PAYMENT_IN_PROGRESS (422). */
        data class Refused(val reason: String, val details: Map<String, Any>) : Failure(reason, FailureKind.BUSINESS_RULE, meta = details)
    }
}
