package com.kiva.application.usecase.account

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundRepository

/**
 * «سفارش جاری» (roadmap phase 14, decision 5): the work still open between the shop and a customer, which keeps them
 * from deleting their account (`422 ACCOUNT_HAS_ACTIVE_ORDERS`). An order is active when it is in one of [ACTIVE]
 * (waiting for the money, held in a reservation, being prepared or photographed, on its way, or being returned), when a
 * change left something owed, or when one of its refunds is still on its way (PENDING or PROCESSING): money in either
 * direction is open work. A delivered, cancelled, expired or refunded order with nothing open is not.
 *
 * Runs inside the caller's transaction: deleting the account asks again after it took its locks (decision 7).
 */
class ActiveOrdersCheck(
    private val orderQmRepository: OrderQmRepository,
    private val refundRepository: RefundRepository,
) {

    /** The codes of [userId]'s active orders, oldest first (the 422's `meta.orderCodes`); empty when there is none. */
    fun activeOrderCodesOf(userId: UserId): List<OrderCode> = activeOf(orderQmRepository.findAllOfCustomer(userId)).map { it.code }

    /** Those of [orders] (one customer's) that are active. */
    fun activeOf(orders: List<OrderQm>): List<OrderQm> {
        val refundOpen = refundRepository.findAllOfOrders(orders.map { it.orderId })
            .filter { it.status == RefundStatus.PENDING || it.status == RefundStatus.PROCESSING }
            .map { it.orderId }
            .toSet()
        return orders.filter { isActive(it.status, it.balanceDue, refundOpen = it.orderId in refundOpen) }
    }

    companion object {
        /** Decision 5 for one order: its [status], what it still owes, and whether one of its refunds is on its way. */
        fun isActive(status: OrderStatus, balanceDue: Money, refundOpen: Boolean): Boolean =
            status in ACTIVE || balanceDue > Money.ZERO || refundOpen

        /**
         * The statuses of an order that is still under way. PAYMENT_FAILED too (decision 17): an attempt failed, but the
         * order may still be paid until its time runs out, and an attempt may be on its way back from the bank.
         */
        val ACTIVE: Set<OrderStatus> = setOf(
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.PAYMENT_FAILED,
            OrderStatus.RESERVED,
            OrderStatus.PROCESSING,
            OrderStatus.PHOTO_SENT,
            OrderStatus.CHANGE_REQUESTED,
            OrderStatus.SHIPPED,
            OrderStatus.RETURN_REQUESTED,
            OrderStatus.RETURNED,
        )
    }
}
