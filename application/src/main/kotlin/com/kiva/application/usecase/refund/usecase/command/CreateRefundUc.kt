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
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import java.time.Clock

/**
 * A refund staff open by hand (`POST /admin/orders/{orderCode}/refunds`; ADMIN only): PENDING, expected within 72 hours,
 * then followed with `PATCH /admin/refunds/{refundId}`. Nothing is sent to a bank or a gateway (roadmap section 1, row 3).
 *
 * The order's row is locked first (roadmap section 4.11, lock 9), then its refunds: two refunds opened at once are counted
 * one after the other, so the refunds that aren't FAILED never add up to more than the order's successful payments
 * (RefundDomainService; 400 REFUND_EXCEEDS_PAID on `amount` with what is refundable). A refund of a return (`returnId`)
 * comes with the returns phase; until then every `returnId` is 400 RETURN_NOT_FOUND. One REFUND_CREATED row in the
 * change log. A panel form: never tried again.
 */
class CreateRefundUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val refundDomainService: RefundDomainService,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateRefundUc.Cmd, RefundDto, CreateRefundUc.Failure> {

    private val logger = getLogger()

    /** The body as it came. [reason] is staff's: [toString] hides it. */
    data class Cmd(val orderCode: String, val amount: Long?, val reason: String?, val method: String?, val returnId: Long?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(orderCode=$orderCode, amount=$amount, method=$method, returnId=$returnId, actor=${actor.userId.value})"
    }

    private data class Input(val amount: Money, val reason: RefundReason, val method: RefundMethod)

    override suspend fun execute(command: Cmd): Result<RefundDto, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("CreateRefundUc.execute orderCode=${code?.value ?: "(not a code)"} amount=${command.amount} method=${command.method} actor=${command.actor.userId.value}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (code == null) return Result.failure(Failure.OrderNotFound)
        val checked = (input as Result.Success).value

        return transactionManager.inTransaction {
            val read = orderByCode.findForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            // Lock 9: the order's row, before anything is counted.
            orderQmRepository.lockOrder(read.orderId) ?: error("Order ${read.code.value} went from the read model")
            val order = orderRepository.get(read.orderId) ?: error("Order ${read.code.value} of the read model has no events")
            val payments = paymentRepository.findAttemptsOf(order.id)
            val refunds = refundRepository.lockAllOf(order.id)
            val now = clock.instant()
            val refund = when (
                val opened = refundDomainService.open(
                    order, payments, refunds, checked.amount, checked.method, checked.reason, RefundSource.MANUAL, command.actor.userId, now,
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
        } ?: RefundMethod.ORIGINAL_PAYMENT
        // The returns phase gives returnId its meaning; until then nothing it names exists.
        if (command.returnId != null) problems += FieldFailure("returnId", Problem.ReturnNotFound)
        return if (problems.isEmpty()) Result.success(Input(amount!!, reason!!, method)) else Result.failure(Failure.InvalidInput(problems))
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object AmountInvalid : Problem("REFUND_AMOUNT_INVALID")
        data object ReturnNotFound : Problem("RETURN_NOT_FOUND")
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** More than the order's money still allows (400 on `amount`, with `refundable`). */
        data class ExceedsPaid(val details: Map<String, Any>) : Failure("REFUND_EXCEEDS_PAID", FailureKind.INVALID, "amount", meta = details)
    }
}
