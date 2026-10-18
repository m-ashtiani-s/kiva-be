package com.kiva.application.usecase.order

import com.kiva.application.dto.RefundDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.repository.DiscountRedemptionRepository
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.refund.RefundDomainService
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.repository.RefundIdGenerator
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import java.time.Instant

/**
 * Cancelling a paid order with everything that goes with it (roadmap section 7.2, row 3), one place for the customer's
 * cancel, the panel's and phase 3's change request (Rule 15), so no path forgets a part: OrderCancelled, the stock given
 * back, `soldCount` taken back (the projector), the code's use given back, and one PENDING refund of everything paid that
 * isn't promised already, BANK_TRANSFER (staff call the customer for the IBAN, which is never stored).
 *
 * Runs inside the caller's transaction, taking the locks in section 4.11's order: the code's use (5), the order's
 * payments (6), the order's row (9), its refunds, then the stock (7) and the order's stream (8). A payment of the order
 * being confirmed right now stops it (422 PAYMENT_IN_PROGRESS): its money would come after the cancellation, with no
 * refund made for it.
 */
class OrderCancellation(
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val discountRedemptionRepository: DiscountRedemptionRepository,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val refundRepository: RefundRepository,
    private val refundIdGenerator: RefundIdGenerator,
    private val refundDomainService: RefundDomainService,
) {
    private val logger = getLogger()

    /**
     * Cancels the order [orderId] for [who] ([reason] the customer's, [note] shown on their timeline) by [by] at [now];
     * the refund it opened, or null when nothing was paid.
     *
     * [answering] is the change request a cancellation answers (a CANCEL_ITEM of the only line, phase 3), read before the
     * locks: if the order, locked, no longer has that very request open (staff resolved it meanwhile), nothing happens
     * and ConcurrentChangeException makes the caller decide again.
     */
    fun run(
        orderId: OrderId,
        who: CancelledBy,
        reason: CancelReason?,
        note: StatusNote?,
        by: UserId,
        now: Instant,
        answering: ChangeRequest? = null,
    ): Result<Cancelled, Failure> {
        logger.info("OrderCancellation.run orderId=${orderId.value} who=$who reason=$reason answering=$answering")
        val redemption = discountRedemptionRepository.lockOfOrder(orderId)
        val payments = paymentRepository.lockAttemptsOf(orderId)
        if (payments.any { it.status == PaymentStatus.VERIFYING }) return Result.failure(Failure.PaymentInProgress)
        orderQmRepository.lockOrder(orderId) ?: error("Order ${orderId.value} is not in the read model")
        val order = orderRepository.get(orderId) ?: error("Order ${orderId.value} of the read model has no events")
        if (answering != null && order.openChange != answering) {
            logger.info("OrderCancellation: order ${order.code.value} no longer has the change request it answers open; deciding again")
            throw ConcurrentChangeException(OrderDomainEvent.AGGREGATE_NAME, EventAggregateId.of(orderId.value))
        }

        when (val cancelled = order.cancel(who, reason, note, by, now)) {
            is Result.Success -> {}
            is Result.Failure -> return Result.failure(failureOf(cancelled.failure))
        }
        val refunds = refundRepository.lockAllOf(orderId)
        inventoryDomainService.releaseForOrder(order.code, order.lines, by, now).forEach(inventoryRepository::save)
        orderRepository.save(order)
        if (redemption != null && redemption.giveBack(now)) discountRedemptionRepository.save(redemption)

        val refundable = refundDomainService.refundable(refundDomainService.paidBy(payments), refunds)
        val refund = refundable.takeIf { it > Money.ZERO }?.let {
            Refund.start(refundIdGenerator.nextRefundId(), orderId, it, RefundMethod.BANK_TRANSFER, RefundSource.CANCELLATION, null, by, now)
                .also(refundRepository::add)
        }
        logger.info("OrderCancellation: order ${order.code.value} cancelled by $who, stock given back, refund ${refund?.id?.value ?: "none"}")
        return Result.success(Cancelled(order, refund?.let(RefundDto::from)))
    }

    private fun failureOf(failure: Order.CancelFailure): Failure = when (failure) {
        is Order.CancelFailure.NotCancellable -> Failure.NotCancellable(failure.meta)
        is Order.CancelFailure.InvalidTransition -> Failure.InvalidTransition(failure.meta)
    }

    /** The cancelled [order] and the refund opened for it, if anything was paid. */
    data class Cancelled(val order: Order, val refund: RefundDto?)

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** The customer's: the order's status moved, or it is cancelled already (422). */
        data class NotCancellable(val details: Map<String, Any>) : Failure("ORDER_NOT_CANCELLABLE", FailureKind.BUSINESS_RULE, details)

        /** Staff's: shipped already, or never paid (422, with `allowed`). */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, details)

        /** A payment of the order is being confirmed right now (422); try again in a moment. */
        data object PaymentInProgress : Failure("PAYMENT_IN_PROGRESS", FailureKind.BUSINESS_RULE)
    }
}
