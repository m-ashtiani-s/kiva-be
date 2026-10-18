package com.kiva.application.dto

import com.kiva.application.repository.OrderTimelineKind
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentOutcome
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * One order's page (the contract's OrderDetail): its card, and everything it was placed with, as it was then. Without
 * the Persian words: the labels of the timeline, the address's full text and the waiting messages are the HTTP layer's.
 *
 * @property payment its latest attempt to pay; null for an order that had nothing to pay.
 * @property photos the photos before shipping: the batch sent, and the operator's draft (the panel's only).
 * @property timeline one entry per event the customer sees, oldest first.
 */
data class OrderDetailDto(
    val summary: OrderSummaryDto,
    val items: List<Item>,
    val address: Address,
    val totals: OrderTotals,
    val payment: Payment?,
    val messenger: Messenger,
    val photos: PreShipmentMediaDto,
    val timeline: List<TimelineEntry>,
    val customerNote: String?,
    val gift: Gift?,
) {
    /**
     * One line, as bought. The color's [Color.isLight] and [Color.sortOrder] are the palette's now; its name and hex
     * those of the purchase.
     */
    data class Item(
        val lineId: Int,
        val productId: Long,
        val variantId: Long,
        val sku: String,
        val name: String,
        val slug: String,
        val color: Color,
        val imageUrl: String?,
        val quantity: Int,
        val unitPrice: Money,
        val unitCompareAtPrice: Money?,
        val lineTotal: Money,
    )

    data class Color(val key: String, val name: String, val hex: String, val isLight: Boolean, val sortOrder: Int)

    /** Where it goes, as it was when placed. Personal data: [toString] shows none of it. */
    data class Address(
        val provinceName: String,
        val cityName: String,
        val addressLine: String,
        val plaque: String?,
        val unit: String?,
        val postalCode: String,
        val recipientName: String,
        val recipientPhone: String,
    ) {
        override fun toString(): String = "Address(****)"
    }

    /** @property gatewayName as the shop's settings call it now. */
    data class Payment(
        val gateway: PaymentGatewayCode,
        val gatewayName: String,
        val outcome: PaymentOutcome,
        val refId: String?,
        val paidOn: Instant?,
        val cardMask: String?,
    )

    /** Where the photos before shipping go; [username] is the customer's handle there. Personal data: [toString] shows the channel only. */
    data class Messenger(val channel: MessengerChannel, val phone: String, val username: String?, val note: String?) {
        override fun toString(): String = "Messenger(channel=$channel)"
    }

    /**
     * One line of the timeline: [kind] says what happened, [status] what the order was then.
     *
     * @property note a cancellation's or a delivery's note, shown as written; @property amount money given back.
     */
    data class TimelineEntry(
        val kind: OrderTimelineKind,
        val status: OrderStatus,
        val at: Instant,
        val failureReason: PaymentFailureReason?,
        val note: String?,
        val amount: Money?,
    )

    data class Gift(val message: String?, val hidePrices: Boolean)
}
