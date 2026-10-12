package com.kiva.application.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * One order as its read model has it: what the customer's lists and pages and the jobs read, instead of its events.
 * The same values the order's events hold, plus its [timeline]. Its payments are not here (payment_tbl has them).
 *
 * Personal data inside: [toString] shows only the order's id, code and status.
 *
 * @property timeline one entry per event, oldest first (`OrderDetail.timeline`).
 */
data class OrderQm(
    val orderId: OrderId,
    val code: OrderCode,
    val userId: UserId,
    val status: OrderStatus,
    val placedOn: Instant,
    val paidOn: Instant?,
    val holdUntil: Instant,
    val shippingMethod: ShippingMethodCode,
    val reserveRequested: Boolean,
    val lines: List<OrderLine>,
    val address: AddressSnapshot,
    val messenger: PreShipmentMessenger,
    val totals: OrderTotals,
    val timeline: List<TimelineEntry>,
    val customerNote: CustomerNote?,
    val gift: GiftNote?,
) {
    /** What happened to the order and when; [failureReason] for a failed payment attempt. The label is the HTTP layer's. */
    data class TimelineEntry(val status: OrderStatus, val at: Instant, val failureReason: PaymentFailureReason?)

    override fun toString(): String = "OrderQm(orderId=${orderId.value}, code=${code.value}, status=$status)"
}
