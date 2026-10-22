package com.kiva.domain.aggregate.refund

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundReason
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundIdGenerator
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import java.time.Instant

/**
 * What a refund alone can't know: how much of its order is still refundable, from the order's payments and its other
 * refunds (roadmap section 3, row 6). Never more than was paid: the refunds of an order that aren't FAILED add up to at
 * most what its successful payments brought in. «Paid» is the sum of the order's SUCCEEDED attempts; an attempt the
 * gateway gave back by itself (REFUNDED) brought nothing.
 *
 * It never saves and never opens a transaction; the use case holds the order's row locked (lock 9) while it asks, so two
 * refunds opened at once are counted one after the other.
 */
class RefundDomainService(
    private val refundIdGenerator: RefundIdGenerator,
) {
    private val logger = getLogger()

    /** What the order's money still allows: paid, minus every refund that isn't FAILED. Never negative. */
    fun refundable(paid: Money, existing: List<Refund>): Money {
        val promised = existing.filter { it.status != RefundStatus.FAILED }.map { it.amount }.sum()
        return if (promised >= paid) Money.ZERO else paid - promised
    }

    /** What [payments] brought in: the amounts of the SUCCEEDED attempts. */
    fun paidBy(payments: List<Payment>): Money = payments.filter { it.status == PaymentStatus.SUCCEEDED }.map { it.amount }.sum()

    /**
     * A new PENDING refund of [amount] for [order], when that much is still refundable; otherwise [OpenFailure.ExceedsPaid]
     * with what is (400 on `amount`). [payments] and [existing] are all of the order's. [returnId] is the return whose
     * money it gives back (source RETURN, phase 7).
     */
    fun open(
        order: Order,
        payments: List<Payment>,
        existing: List<Refund>,
        amount: Money,
        method: RefundMethod,
        reason: RefundReason?,
        source: RefundSource,
        by: UserId,
        now: Instant,
        returnId: ReturnId? = null,
    ): Result<Refund, OpenFailure> {
        logger.info("open orderId=${order.id.value} amount=${amount.toman} method=$method source=$source returnId=${returnId?.value}")
        require(payments.all { it.orderId == order.id } && existing.all { it.orderId == order.id }) { "Payments or refunds of another order than ${order.id.value}" }
        require(amount > Money.ZERO) { "A refund of nothing for order ${order.id.value}" }

        val refundable = refundable(paidBy(payments), existing)
        if (amount > refundable) return Result.failure(OpenFailure.ExceedsPaid(refundable))
        return Result.success(Refund.start(refundIdGenerator.nextRefundId(), order.id, amount, method, source, reason, by, now, returnId))
    }

    sealed class OpenFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        /** More than the order's money still allows. */
        data class ExceedsPaid(val refundable: Money) :
            OpenFailure("REFUND_EXCEEDS_PAID", FailureKind.INVALID, "amount", mapOf("refundable" to refundable.toman))
    }
}
