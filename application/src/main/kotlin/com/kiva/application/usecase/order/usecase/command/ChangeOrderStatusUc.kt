package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
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
import com.kiva.domain.aggregate.order.model.policy.OrderTransitionPolicy
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * Staff move an order to another status (`POST /admin/orders/{orderCode}/status`; ADMIN, OPERATOR), only where
 * OrderTransitionPolicy lets them (422 INVALID_STATUS_TRANSITION with `from`, `to` and `allowed`): CANCELLED before it
 * ships (OrderCancellation, with everything that goes with it). The other statuses have endpoints of their own.
 *
 * `note` is shown on the customer's timeline; with `notifyCustomer` (by default) they get an SMS after the commit. One
 * ORDER_STATUS_CHANGED row in the change log, the status from and to. A change of the order at the same moment (the
 * customer cancelling too) makes the whole transaction run again, up to [MAX_ATTEMPTS] times, deciding again.
 */
class ChangeOrderStatusUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderCancellation: OrderCancellation,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val orderNotices: OrderNotices,
    private val clock: Clock,
) : CommandUseCase<ChangeOrderStatusUc.Cmd, Unit, ChangeOrderStatusUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [note] is shown to the customer, never logged ([toString]). */
    data class Cmd(val orderCode: String, val status: String?, val note: String?, val notifyCustomer: Boolean?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(orderCode=$orderCode, status=$status, notifyCustomer=$notifyCustomer, actor=${actor.userId.value})"
    }

    private data class Input(val status: OrderStatus, val note: StatusNote?, val notify: Boolean)

    private data class Changed(val code: OrderCode, val status: OrderStatus, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("ChangeOrderStatusUc.execute orderCode=${code?.value ?: "(not a code)"} status=${command.status} actor=${command.actor.userId.value}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (code == null) return Result.failure(Failure.OrderNotFound)
        val checked = (input as Result.Success).value

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return changeOnce(command, checked)
            } catch (e: ConcurrentChangeException) {
                logger.info("ChangeOrderStatusUc: order ${code.value} changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        return changeOnce(command, checked)
    }

    private suspend fun changeOnce(command: Cmd, input: Input): Result<Unit, Failure> {
        val changed = transactionManager.inTransaction {
            val read = orderByCode.findForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val allowed = OrderTransitionPolicy.allowedFrom(read.status)
            if (input.status !in allowed) return@inTransaction Result.failure(Failure.InvalidTransition(read.status, input.status, allowed))
            val now = clock.instant()
            when (input.status) {
                OrderStatus.CANCELLED ->
                    when (val cancelled = orderCancellation.run(read.orderId, CancelledBy.STAFF, null, input.note, command.actor.userId, now)) {
                        is Result.Success -> {}
                        is Result.Failure -> return@inTransaction Result.failure(Failure.Refused(cancelled.failure.code, cancelled.failure.meta))
                    }
                else -> error("${input.status} is allowed from ${read.status} but has no way here")
            }
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.ORDER_STATUS_CHANGED, AuditEntityType.ORDER, read.code.value, read.code.value,
                    AuditDiff.build { field("status", read.status, input.status) },
                ),
            )
            Result.success(Changed(read.code, input.status, if (input.notify) userRepository.findById(read.userId)?.phone else null))
        }
        return when (changed) {
            is Result.Success -> {
                if (input.notify && changed.value.status == OrderStatus.CANCELLED) orderNotices.cancelled(changed.value.to, changed.value.code)
                Result.success(Unit)
            }
            is Result.Failure -> changed
        }
    }

    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val status = OrderStatus.entries.firstOrNull { it.name == command.status?.trim() }
            ?: null.also { problems += FieldFailure("status", Problem.StatusInvalid) }
        val note = when (val parsed = StatusNote.makeNew(command.note)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        return if (problems.isEmpty()) Result.success(Input(status!!, note, command.notifyCustomer ?: true)) else Result.failure(Failure.InvalidInput(problems))
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object StatusInvalid : Problem("ORDER_STATUS_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not a move `…/status` takes from the order's status (422). */
        data class InvalidTransition(val from: OrderStatus, val to: OrderStatus, val allowed: Set<OrderStatus>) : Failure(
            "INVALID_STATUS_TRANSITION",
            FailureKind.BUSINESS_RULE,
            meta = mapOf("from" to from.name, "to" to to.name, "allowed" to allowed.map { it.name }.sorted()),
        )

        /** The order's own rules refused it (422): PAYMENT_IN_PROGRESS, or what changed meanwhile. */
        data class Refused(val reason: String, val details: Map<String, Any>) : Failure(reason, FailureKind.BUSINESS_RULE, meta = details)
    }
}
