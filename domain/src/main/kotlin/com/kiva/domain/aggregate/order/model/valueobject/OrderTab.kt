package com.kiva.domain.aggregate.order.model.valueobject

/**
 * The tabs of «سفارش‌های من» (`GET /me/orders?filter=`), besides «همه»: which tab an order is in follows from its status
 * alone, so the list's filter and its counts never disagree.
 *
 * An order that expired unpaid is in [CANCELLED], not [CURRENT]: nothing more will happen to it, and «جاری» is for
 * orders the customer is still waiting on (the frontend's mock does the same). An unpaid order still in its time to pay
 * is [CURRENT]: it can still be paid. The statuses of later phases are placed now, so the tabs don't move when they come:
 * returns happen to delivered orders, and money given back ends an order.
 */
enum class OrderTab {
    CURRENT,
    DELIVERED,
    CANCELLED,
    ;

    /** The statuses whose orders this tab lists. */
    val statuses: Set<OrderStatus> get() = OrderStatus.entries.filter { of(it) == this }.toSet()

    companion object {
        fun of(status: OrderStatus): OrderTab = when (status) {
            OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, OrderStatus.RESERVED, OrderStatus.PROCESSING, OrderStatus.PHOTO_SENT,
            OrderStatus.CHANGE_REQUESTED, OrderStatus.SHIPPED,
            -> CURRENT
            OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED -> DELIVERED
            OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.REFUNDED -> CANCELLED
        }
    }
}
