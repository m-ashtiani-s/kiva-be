package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.infrastructure.persistence.event.serializer.payload.order.AddressSnapshotPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.GiftNotePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderLinePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderTotalsPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.PreShipmentMessengerPayload
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderTimelineEntry
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * Keeps orderqm_tbl in step with the orders' events: OrderRepositoryImpl calls [project] for each event right after
 * storing it, in the same transaction. Each event changes only its own order's row, and adds one entry to its timeline,
 * so `OrderDetail.timeline` comes from the events without anyone reading the event store.
 *
 * A payment also counts the bags as sold: `soldCount` of each product of the order goes up by its quantity, so «پرفروش‌ترین»
 * is real. That changes product rows, which other writers lock too (the stock's projector, a product's own); so the
 * repository takes those locks with [lockFor] before it stores anything, in the order every writer uses (roadmap
 * section 11: the products' rows, ascending, before the order's stream).
 *
 * Runs inside the caller's transaction; it never opens one itself.
 */
object OrderQmProjector {

    /**
     * Locks, before [events] are stored, the product rows their projection will change: those of [lines]' products, in
     * ascending productId, when one of the events is a payment. A lock this transaction already holds (the stock it
     * just reserved for a new order) is simply held again.
     */
    fun lockFor(events: List<OrderDomainEvent>, lines: List<OrderLine>) {
        if (events.none { it is OrderPaid }) return
        lines.map { it.productId.value }.distinct().sorted().forEach(ProductQmSummary::lock)
    }

    fun project(event: OrderDomainEvent) {
        when (event) {
            is OrderPlaced -> insert(event)
            is OrderPaymentFailed -> moveTo(event, OrderStatus.PAYMENT_FAILED, failureReason = event.reason)
            is OrderPaid -> {
                moveTo(event, if (event.heldForReservation) OrderStatus.RESERVED else OrderStatus.PROCESSING, paidOn = event.occurredOn, group = event.reservationGroupId?.value)
                countSold(event.orderId.value)
            }
            is OrderExpired -> moveTo(event, OrderStatus.EXPIRED)
            is OrderReleasedFromReservation -> moveTo(event, OrderStatus.PROCESSING)
        }
    }

    private fun insert(event: OrderPlaced) {
        OrderQmTbl.insert {
            it[orderId] = event.orderId.value
            it[code] = event.code.value
            it[userId] = event.userId.value
            it[status] = OrderStatus.PENDING_PAYMENT
            it[placedOn] = event.occurredOn.utc()
            it[paidOn] = null
            it[holdUntil] = event.holdUntil.utc()
            it[payable] = event.totals.payable.toman
            it[itemsCount] = event.totals.itemsCount
            it[shippingMethod] = event.shippingMethod
            it[messengerChannel] = event.messenger.channel
            it[reserveRequested] = event.reserveRequested
            it[reservationGroupId] = event.reservationGroupId?.value
            it[lines] = event.lines.map(::OrderLinePayload)
            it[address] = AddressSnapshotPayload(event.address)
            it[messenger] = PreShipmentMessengerPayload(event.messenger)
            it[totals] = OrderTotalsPayload(event.totals)
            it[timeline] = listOf(entry(OrderStatus.PENDING_PAYMENT, event.occurredOn, null))
            it[gift] = event.gift?.let(::GiftNotePayload)
            it[customerNote] = event.customerNote?.value
            it[updatedOn] = event.occurredOn.utc()
        }
    }

    /**
     * The order's new status, with one more timeline entry. No lock of its own is needed: two transactions changing
     * one order are already one after the other at its stream, where the second is refused.
     */
    private fun moveTo(event: OrderDomainEvent, status: OrderStatus, failureReason: PaymentFailureReason? = null, paidOn: Instant? = null, group: Long? = null) {
        val orderId = event.orderId.value
        val timeline = OrderQmTbl.select(OrderQmTbl.timeline).where { OrderQmTbl.orderId eq orderId }.singleOrNull()?.get(OrderQmTbl.timeline)
            ?: error("${event.type.value} of order $orderId, which the read model doesn't have")

        val updated = OrderQmTbl.update({ OrderQmTbl.orderId eq orderId }) {
            it[OrderQmTbl.status] = status
            it[OrderQmTbl.timeline] = timeline + entry(status, event.occurredOn, failureReason)
            if (paidOn != null) it[OrderQmTbl.paidOn] = paidOn.utc()
            if (group != null) it[OrderQmTbl.reservationGroupId] = group
            it[updatedOn] = event.occurredOn.utc()
        }
        check(updated == 1) { "Order $orderId: updated $updated rows of the read model instead of 1" }
    }

    /** Each product of the paid order sold its quantity, all its colors together; ascending productId, as locked. */
    private fun countSold(orderId: Long) {
        val lines = OrderQmTbl.select(OrderQmTbl.lines).where { OrderQmTbl.orderId eq orderId }.single()[OrderQmTbl.lines]
        lines.groupBy({ it.productId }, { it.quantity }).toSortedMap().forEach { (productId, quantities) ->
            ProductQmSummary.lock(productId)
            val updated = ProductQmTbl.update({ ProductQmTbl.productId eq productId }) {
                it[soldCount] = soldCount + quantities.sum()
            }
            check(updated == 1) { "Product $productId: updated $updated rows of the read model instead of 1" }
        }
    }

    private fun entry(status: OrderStatus, at: Instant, failureReason: PaymentFailureReason?) =
        OrderTimelineEntry(status.name, at.toString(), failureReason?.name)

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
