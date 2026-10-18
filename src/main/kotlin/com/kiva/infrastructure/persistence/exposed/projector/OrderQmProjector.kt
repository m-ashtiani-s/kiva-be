package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.repository.OrderTimelineKind
import com.kiva.application.repository.ResolutionDecision
import com.kiva.common.text.normalizePersian
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.entity.event.OrderBalancePaid
import com.kiva.domain.aggregate.order.entity.event.OrderCancelled
import com.kiva.domain.aggregate.order.entity.event.OrderChangeApplied
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRejected
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRequested
import com.kiva.domain.aggregate.order.entity.event.OrderDelivered
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderLineRemoved
import com.kiva.domain.aggregate.order.entity.event.OrderLineReplaced
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosApproved
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosAttached
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosSent
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderRefunded
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.entity.event.OrderShipmentCorrected
import com.kiva.domain.aggregate.order.entity.event.OrderShipped
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.infrastructure.persistence.event.serializer.payload.order.AddressSnapshotPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.GiftNotePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderLinePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPhotoPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderTotalsPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.PreShipmentMessengerPayload
import com.kiva.infrastructure.persistence.exposed.tbl.OrderCancellationQm
import com.kiva.infrastructure.persistence.exposed.tbl.OrderFeedbackQm
import com.kiva.infrastructure.persistence.exposed.tbl.OrderPhotosQm
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderResolutionQm
import com.kiva.infrastructure.persistence.exposed.tbl.OrderTimelineEntry
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * Keeps orderqm_tbl in step with the orders' events: OrderRepositoryImpl calls [project] for each event right after
 * storing it, in the same transaction. Each event changes only its own order's row, and every event the customer sees
 * adds one entry of its kind to the timeline, so `OrderDetail.timeline` comes from the events without anyone reading
 * the event store. The operator's draft of photos (OrderPhotosAttached) is the panel's only: no entry.
 *
 * A payment also counts the bags as sold: `soldCount` of each product of the order goes up by its quantity, so «پرفروش‌ترین»
 * is real, and so does each color's (the panel's AdminVariant.soldCount); a cancellation takes exactly that back, and a
 * change request moves it: a swapped line's count from the old color to the new one, a line taken out's back. That
 * changes product rows, which other writers lock too (the stock's projector, a product's own); so the repository takes
 * those locks with [lockFor] before it stores anything, in the order every writer uses (roadmap section 4.11: the
 * products' rows, ascending, lock 7, before the order's stream, lock 8).
 *
 * Runs inside the caller's transaction; it never opens one itself.
 */
object OrderQmProjector {

    /**
     * Locks, before [events] are stored, the product rows their projection will change, in ascending productId: those of
     * [lines]' products (the order's lines after the events) when one of the events is a payment or a cancellation, and,
     * for a line swapped or taken out, the products of the lines before (still in the read model) and of the new lines.
     * A lock this transaction already holds (the stock it just reserved or gave back) is simply held again.
     */
    fun lockFor(events: List<OrderDomainEvent>, lines: List<OrderLine>) {
        val products = mutableSetOf<Long>()
        if (events.any { it is OrderPaid || it is OrderCancelled }) products += lines.map { it.productId.value }
        val changed = events.filter { it is OrderLineReplaced || it is OrderLineRemoved }
        if (changed.isNotEmpty()) {
            products += linesOf(changed.first().orderId.value).map { it.productId }
            products += events.filterIsInstance<OrderLineReplaced>().map { it.line.productId.value }
        }
        products.sorted().forEach(ProductQmSummary::lock)
    }

    fun project(event: OrderDomainEvent) {
        when (event) {
            is OrderPlaced -> insert(event)
            is OrderPaymentFailed -> moveTo(event, OrderStatus.PAYMENT_FAILED, OrderTimelineKind.PAYMENT_FAILED, failureReason = event.reason)
            is OrderPaid -> {
                val status = if (event.heldForReservation) OrderStatus.RESERVED else OrderStatus.PROCESSING
                moveTo(event, status, OrderTimelineKind.PAID) { _, it ->
                    it[OrderQmTbl.paidOn] = event.occurredOn.utc()
                    event.reservationGroupId?.let { group -> it[OrderQmTbl.reservationGroupId] = group.value }
                }
                countSold(event.orderId.value, +1)
            }
            is OrderExpired -> moveTo(event, OrderStatus.EXPIRED, OrderTimelineKind.EXPIRED)
            is OrderReleasedFromReservation -> moveTo(event, OrderStatus.PROCESSING, OrderTimelineKind.RELEASED_FROM_RESERVATION)
            is OrderPhotosAttached -> change(event) { row, it ->
                it[OrderQmTbl.photos] = row.photos.copy(draft = event.items.map(::OrderPhotoPayload))
            }
            is OrderPhotosSent -> moveTo(event, OrderStatus.PHOTO_SENT, OrderTimelineKind.PHOTOS_SENT) { row, it ->
                it[OrderQmTbl.photos] = OrderPhotosQm(draft = emptyList(), sent = row.photos.draft, batch = event.batchNo)
                it[OrderQmTbl.photoStatus] = PhotoStatus.SENT
                it[OrderQmTbl.photoEverSent] = true
                it[OrderQmTbl.photosSentOn] = event.occurredOn.utc()
                it[OrderQmTbl.feedbackDeadline] = event.feedbackDeadline.utc()
                // New photos wait for a new answer.
                it[OrderQmTbl.feedback] = null
            }
            is OrderPhotosApproved -> {
                val kind = if (event.automatic) OrderTimelineKind.PHOTOS_AUTO_APPROVED else OrderTimelineKind.PHOTOS_APPROVED
                stay(event, kind) { _, it ->
                    it[OrderQmTbl.photoStatus] = PhotoStatus.APPROVED
                    if (!event.automatic) {
                        it[OrderQmTbl.feedback] = OrderFeedbackQm(FeedbackDecision.APPROVE.name, null, null, null, null, event.occurredOn.toString(), null)
                    }
                }
            }
            is OrderShipped -> moveTo(event, OrderStatus.SHIPPED, OrderTimelineKind.SHIPPED) { _, it ->
                it[OrderQmTbl.trackingCode] = event.trackingCode.value
                it[OrderQmTbl.shippedOn] = event.shippedOn.utc()
                it[OrderQmTbl.showInDailyList] = event.showInDailyList
            }
            is OrderShipmentCorrected -> stay(event, OrderTimelineKind.SHIPMENT_CORRECTED) { _, it ->
                it[OrderQmTbl.trackingCode] = event.trackingCode.value
                it[OrderQmTbl.shippedOn] = event.shippedOn.utc()
                it[OrderQmTbl.showInDailyList] = event.showInDailyList
            }
            is OrderDelivered -> moveTo(event, OrderStatus.DELIVERED, OrderTimelineKind.DELIVERED, note = event.note?.value) { _, it ->
                it[OrderQmTbl.deliveredOn] = event.occurredOn.utc()
                it[OrderQmTbl.returnableUntil] = event.returnableUntil?.utc()
            }
            is OrderCancelled -> {
                moveTo(event, OrderStatus.CANCELLED, OrderTimelineKind.CANCELLED, note = event.note?.value) { _, it ->
                    it[OrderQmTbl.cancelledOn] = event.occurredOn.utc()
                    it[OrderQmTbl.cancellation] = OrderCancellationQm(event.by.name, event.reason?.name, event.note?.value)
                    // A cancelled order owes nothing.
                    it[OrderQmTbl.balanceDue] = 0
                }
                countSold(event.orderId.value, -1)
            }
            is OrderRefunded -> stay(event, OrderTimelineKind.REFUNDED, amount = event.amount.toman) { row, it ->
                it[OrderQmTbl.refunded] = row.refunded + event.amount.toman
            }
            is OrderChangeRequested -> moveTo(event, OrderStatus.CHANGE_REQUESTED, OrderTimelineKind.CHANGE_REQUESTED) { _, it ->
                it[OrderQmTbl.photoStatus] = PhotoStatus.CHANGE_REQUESTED
                it[OrderQmTbl.feedback] = OrderFeedbackQm(
                    FeedbackDecision.REQUEST_CHANGE.name, event.changeType.name, event.lineId, event.desiredVariantId?.value, event.note?.value,
                    event.occurredOn.toString(), resolution = null,
                )
            }
            is OrderChangeRejected -> moveTo(event, OrderStatus.PROCESSING, OrderTimelineKind.CHANGE_REJECTED, note = event.note.value) { row, it ->
                it[OrderQmTbl.photoStatus] = PhotoStatus.WAITING
                it[OrderQmTbl.feedback] = row.resolved(OrderResolutionQm(ResolutionDecision.REJECT.name, null, event.note.value, event.occurredOn.toString()))
            }
            // The line changes now; the customer's timeline gets one entry when the change is applied.
            is OrderLineReplaced -> {
                val old = lineOf(event.orderId.value, event.lineId)
                change(event) { row, it ->
                    it[OrderQmTbl.lines] = row.lines.map { line -> if (line.lineId == event.lineId) OrderLinePayload(event.line) else line }
                }
                val new = event.line
                countSold(listOf(Sold(old.productId, old.variantId, -old.quantity), Sold(new.productId.value, new.variantId.value, new.quantity.value)))
            }
            is OrderLineRemoved -> {
                val removed = lineOf(event.orderId.value, event.lineId)
                change(event) { row, it ->
                    it[OrderQmTbl.lines] = row.lines.filter { line -> line.lineId != event.lineId }
                    it[OrderQmTbl.itemsCount] = row.itemsCount - removed.quantity
                    // The code's share of the line leaves with it: what it cost minus what was paid for it.
                    it[OrderQmTbl.codeDiscountRemoved] = row.codeDiscountRemoved + (removed.toDomain().totals.total - event.paidShare).toman
                }
                countSold(listOf(Sold(removed.productId, removed.variantId, -removed.quantity)))
            }
            is OrderChangeApplied -> moveTo(event, OrderStatus.PROCESSING, appliedKindOf(event.changeType), note = event.note?.value) { row, it ->
                it[OrderQmTbl.photoStatus] = PhotoStatus.WAITING
                it[OrderQmTbl.balanceDue] = event.balanceDueAfter.toman
                it[OrderQmTbl.feedback] = row.resolved(
                    OrderResolutionQm(ResolutionDecision.APPLY.name, event.variantId?.value, event.note?.value, event.occurredOn.toString()),
                )
            }
            is OrderBalancePaid -> stay(event, OrderTimelineKind.BALANCE_PAID, amount = event.amount.toman) { row, it ->
                it[OrderQmTbl.balanceDue] = 0
                it[OrderQmTbl.balancePaid] = row.balancePaid + event.amount.toman
            }
        }
    }

    /** The timeline's entry of a change carried out: what it did. */
    private fun appliedKindOf(type: ChangeType): OrderTimelineKind = when (type) {
        ChangeType.COLOR -> OrderTimelineKind.COLOR_CHANGED
        ChangeType.MODEL -> OrderTimelineKind.MODEL_CHANGED
        ChangeType.CANCEL_ITEM -> OrderTimelineKind.ITEM_REMOVED
        ChangeType.OTHER -> OrderTimelineKind.CHANGE_APPLIED
    }

    /** The order's lines as the read model has them now (before the events being stored). */
    private fun linesOf(orderId: Long): List<OrderLinePayload> =
        OrderQmTbl.select(OrderQmTbl.lines).where { OrderQmTbl.orderId eq orderId }.singleOrNull()?.get(OrderQmTbl.lines)
            ?: error("Order $orderId, which the read model doesn't have")

    private fun lineOf(orderId: Long, lineId: Int): OrderLinePayload =
        linesOf(orderId).firstOrNull { it.lineId == lineId } ?: error("Line $lineId of order $orderId is not in the read model")

    /** What the order's search finds it by: its code, the recipient's name and phone, and the city, normalized. */
    fun searchTextOf(code: String, recipientName: String, recipientPhone: String, cityName: String): String =
        "$code $recipientName $recipientPhone $cityName".normalizePersian()

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
            it[timeline] = listOf(entry(OrderTimelineKind.PLACED, OrderStatus.PENDING_PAYMENT, event.occurredOn))
            it[gift] = event.gift?.let(::GiftNotePayload)
            it[customerNote] = event.customerNote?.value
            it[updatedOn] = event.occurredOn.utc()
            it[searchText] = event.address.let { address -> searchTextOf(event.code.value, address.recipientName.value, address.recipientPhone.value, address.cityName) }
            it[photoStatus] = PhotoStatus.WAITING
            it[photoEverSent] = false
            it[photos] = OrderPhotosQm.NONE
            it[showInDailyList] = true
            it[refunded] = 0
            it[balanceDue] = 0
            it[balancePaid] = 0
            it[codeDiscountRemoved] = 0
        }
    }

    /** What a change reads of the row before writing it. */
    private class Row(
        val status: OrderStatus,
        val timeline: List<OrderTimelineEntry>,
        val photos: OrderPhotosQm,
        val refunded: Long,
        val lines: List<OrderLinePayload>,
        val itemsCount: Int,
        val feedback: OrderFeedbackQm?,
        val balancePaid: Long,
        val codeDiscountRemoved: Long,
    ) {
        /** The customer's change request, resolved: their answer stays, with how it ended. */
        fun resolved(resolution: OrderResolutionQm): OrderFeedbackQm =
            checkNotNull(feedback) { "A change request resolved without the customer's request" }.copy(resolution = resolution)
    }

    /** The order moves to [status], with one more timeline entry of [kind], and whatever else [also] writes. */
    private fun moveTo(
        event: OrderDomainEvent,
        status: OrderStatus,
        kind: OrderTimelineKind,
        failureReason: PaymentFailureReason? = null,
        note: String? = null,
        also: (Row, UpdateStatement) -> Unit = { _, _ -> },
    ) = write(event) { row, it ->
        it[OrderQmTbl.status] = status
        it[OrderQmTbl.timeline] = row.timeline + entry(kind, status, event.occurredOn, failureReason, note)
        also(row, it)
    }

    /** The status stays; one more timeline entry of [kind], and whatever else [also] writes. */
    private fun stay(event: OrderDomainEvent, kind: OrderTimelineKind, amount: Long? = null, also: (Row, UpdateStatement) -> Unit) = write(event) { row, it ->
        it[OrderQmTbl.timeline] = row.timeline + entry(kind, row.status, event.occurredOn, amount = amount)
        also(row, it)
    }

    /** A change the customer doesn't see: no timeline entry. */
    private fun change(event: OrderDomainEvent, also: (Row, UpdateStatement) -> Unit) = write(event, also)

    /**
     * Writes one change of the order's row. No lock of its own is needed: two transactions changing one order are already
     * one after the other at its stream, where the second is refused.
     */
    private fun write(event: OrderDomainEvent, block: (Row, UpdateStatement) -> Unit) {
        val orderId = event.orderId.value
        val row = OrderQmTbl.select(
            OrderQmTbl.status, OrderQmTbl.timeline, OrderQmTbl.photos, OrderQmTbl.refunded, OrderQmTbl.lines, OrderQmTbl.itemsCount, OrderQmTbl.feedback,
            OrderQmTbl.balancePaid, OrderQmTbl.codeDiscountRemoved,
        )
            .where { OrderQmTbl.orderId eq orderId }
            .singleOrNull()
            ?.let {
                Row(
                    it[OrderQmTbl.status], it[OrderQmTbl.timeline], it[OrderQmTbl.photos], it[OrderQmTbl.refunded], it[OrderQmTbl.lines], it[OrderQmTbl.itemsCount],
                    it[OrderQmTbl.feedback], it[OrderQmTbl.balancePaid], it[OrderQmTbl.codeDiscountRemoved],
                )
            }
            ?: error("${event.type.value} of order $orderId, which the read model doesn't have")

        val updated = OrderQmTbl.update({ OrderQmTbl.orderId eq orderId }) {
            block(row, it)
            it[updatedOn] = event.occurredOn.utc()
        }
        check(updated == 1) { "Order $orderId: updated $updated rows of the read model instead of 1" }
    }

    /** A color of a product sold [quantity] more (or, negative, got some back). */
    private data class Sold(val productId: Long, val variantId: Long, val quantity: Int)

    /** Each line of the order sold ([direction] +1, a payment) or got back (-1, a cancellation) its quantity. */
    private fun countSold(orderId: Long, direction: Int) =
        countSold(linesOf(orderId).map { Sold(it.productId, it.variantId, direction * it.quantity) })

    /**
     * Each product of [sold] sold its colors' quantities together, and each color its own; ascending productId, as
     * locked, each product's row before its colors' rows (ProductQmSummary's lock order). A product or color whose
     * quantities add up to nothing (a line swapped for another color of the same bag) still writes nothing wrong.
     */
    private fun countSold(sold: List<Sold>) {
        sold.groupBy { it.productId }.toSortedMap().forEach { (productId, ofProduct) ->
            ProductQmSummary.lock(productId)
            val quantity = ofProduct.sumOf { it.quantity }
            val updated = ProductQmTbl.update({ ProductQmTbl.productId eq productId }) { it[soldCount] = soldCount + quantity }
            check(updated == 1) { "Product $productId: updated $updated rows of the read model instead of 1" }
            ofProduct.groupBy({ it.variantId }, { it.quantity }).toSortedMap().forEach { (variantId, quantities) ->
                val colorUpdated = ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq variantId }) { it[soldCount] = soldCount + quantities.sum() }
                check(colorUpdated == 1) { "Variant $variantId: updated $colorUpdated rows of the read model instead of 1" }
            }
        }
    }

    private fun entry(
        kind: OrderTimelineKind,
        status: OrderStatus,
        at: Instant,
        failureReason: PaymentFailureReason? = null,
        note: String? = null,
        amount: Long? = null,
    ) = OrderTimelineEntry(status.name, at.toString(), failureReason?.name, kind.name, note, amount)

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
