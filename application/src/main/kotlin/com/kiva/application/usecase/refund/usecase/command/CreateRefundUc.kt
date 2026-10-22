package com.kiva.application.usecase.refund.usecase.command

import com.kiva.application.dto.RefundDto
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.refund.RefundDomainService
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundReason
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import java.time.Clock

/**
 * A refund staff open by hand (`POST /admin/orders/{orderCode}/refunds`; ADMIN only): PENDING, expected within 72 hours,
 * then followed with `PATCH /admin/refunds/{refundId}`. Nothing is sent to a bank or a gateway (roadmap section 1, row 3).
 *
 * The order's row is locked first (roadmap section 4.11, lock 9), then its refunds: two refunds opened at once are counted
 * one after the other, so the refunds that aren't FAILED never add up to more than the order's successful payments
 * (RefundDomainService; 400 REFUND_EXCEEDS_PAID on `amount` with what is refundable).
 *
 * A refund of a return (`returnId`; roadmap section 12.2, decision 15): the return of this order (none, or another
 * order's: 400 RETURN_NOT_FOUND on `returnId`), locked after the order's row (lock 10), RECEIVED (422
 * INVALID_STATUS_TRANSITION) and with no refund that isn't FAILED yet (422 RETURN_REFUND_EXISTS; a partial unique index is
 * the wall behind it). Its source is RETURN, and without a `method` it gives back the way the customer asked for. Its
 * completion makes the return REFUNDED (UpdateRefundUc). One REFUND_CREATED row in the change log. A panel form: never
 * tried again.
 */
class CreateRefundUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val returnRequestRepository: ReturnRequestRepository,
    private val refundDomainService: RefundDomainService,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateRefundUc.Cmd, RefundDto, CreateRefundUc.Failure> {

    private val logger = getLogger()

    /** The body as it came. [reason] is staff's: [toString] hides it. */
    data class Cmd(val orderCode: String, val amount: Long?, val reason: String?, val method: String?, val returnId: Long?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(orderCode=$orderCode, amount=$amount, method=$method, returnId=$returnId, actor=${actor.userId.value})"
    }

    /** [method] null: the return's, or ORIGINAL_PAYMENT. */
    private data class Input(val amount: Money, val reason: RefundReason, val method: RefundMethod?, val returnId: ReturnId?)

    override suspend fun execute(command: Cmd): Result<RefundDto, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("CreateRefundUc.execute orderCode=${code?.value ?: "(not a code)"} amount=${command.amount} method=${command.method} returnId=${command.returnId} actor=${command.actor.userId.value}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (code == null) return Result.failure(Failure.OrderNotFound)
        val checked = (input as Result.Success).value

        return transactionManager.inTransaction {
            val read = orderByCode.findForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            // Lock 9: the order's row, before anything is counted.
            orderQmRepository.lockOrder(read.orderId) ?: error("Order ${read.code.value} went from the read model")
            val order = orderRepository.get(read.orderId) ?: error("Order ${read.code.value} of the read model has no events")
            // Lock 10: the return whose money it is, after its order's row.
            val ret = checked.returnId?.let { returnId ->
                returnRequestRepository.lock(returnId)?.takeIf { it.orderId == order.id }
                    ?: return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("returnId", Problem.ReturnNotFound))))
            }
            val payments = paymentRepository.findAttemptsOf(order.id)
            val refunds = refundRepository.lockAllOf(order.id)
            if (ret != null) {
                val live = refunds.filter { it.returnId == ret.id.value && it.status != RefundStatus.FAILED }
                if (ret.status != ReturnStatus.RECEIVED) {
                    val allowed = ret.allowedTransitions(hasOpenRefund = live.any { !it.status.isFinal }).map { it.name }.sorted()
                    return@inTransaction Result.failure(Failure.InvalidTransition(mapOf("from" to ret.status.name, "to" to ReturnStatus.REFUNDED.name, "allowed" to allowed)))
                }
                if (live.isNotEmpty()) return@inTransaction Result.failure(Failure.ReturnRefundExists)
            }
            val now = clock.instant()
            val method = checked.method ?: ret?.refundMethod ?: RefundMethod.ORIGINAL_PAYMENT
            val source = if (ret == null) RefundSource.MANUAL else RefundSource.RETURN
            val refund = when (
                val opened = refundDomainService.open(
                    order, payments, refunds, checked.amount, method, checked.reason, source, command.actor.userId, now, ret?.id,
                )
            ) {
                is Result.Success -> opened.value
                is Result.Failure -> return@inTransaction Result.failure(Failure.ExceedsPaid(opened.failure.meta))
            }
            refundRepository.add(refund)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.REFUND_CREATED, AuditEntityType.REFUND, refund.id.value.toString(), order.code.value,
                    AuditDiff.build {
                        field("amount", null, refund.amount)
                        field("method", null, refund.method)
                    },
                ),
            )
            Result.success(RefundDto.from(refund))
        }
    }

    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val amount = command.amount?.takeIf { it > 0 }?.let { Money.constructFromPersisted(it) }
            ?: null.also { problems += FieldFailure("amount", Problem.AmountInvalid) }
        val reason = when (val parsed = RefundReason.makeNew(command.reason)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val method = command.method?.trim()?.ifEmpty { null }?.let { raw ->
            when (val parsed = RefundMethod.makeNew(raw)) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }
        // An id that can't be one names no return: the same answer as one nobody has.
        val returnId = command.returnId?.let { raw ->
            ReturnId.makeNew(raw.toString()).getOrNull() ?: null.also { problems += FieldFailure("returnId", Problem.ReturnNotFound) }
        }
        return if (problems.isEmpty()) Result.success(Input(amount!!, reason!!, method, returnId)) else Result.failure(Failure.InvalidInput(problems))
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object AmountInvalid : Problem("REFUND_AMOUNT_INVALID")
        data object ReturnNotFound : Problem("RETURN_NOT_FOUND")
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The return isn't RECEIVED (422), with `meta.from`, `to` and `allowed`. */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)

        /** The return has a refund that isn't FAILED already (422): one return, one money back. */
        data object ReturnRefundExists : Failure("RETURN_REFUND_EXISTS", FailureKind.BUSINESS_RULE)

        /** More than the order's money still allows (400 on `amount`, with `refundable`). */
        data class ExceedsPaid(val details: Map<String, Any>) : Failure("REFUND_EXCEEDS_PAID", FailureKind.INVALID, "amount", meta = details)
    }
}
