package com.kiva.domain.aggregate.returnrequest.model.valueobject

import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * One line of the order going back: its [lineId] (the contract's `orderItemId`) and how many of it. The use case checked
 * the shape (FIELD_REQUIRED, QUANTITY_INVALID); whether the order has that line, it is returnable and has that many is
 * the order's (Order.requestReturn).
 */
@ConsistentCopyVisibility
data class ReturnItem private constructor(val lineId: Int, val quantity: Int) {
    init {
        require(lineId >= 1) { "Invalid return line id $lineId" }
        require(quantity in 1..ReturnPolicy.MAX_QUANTITY) { "Invalid return quantity $quantity" }
    }

    companion object {
        /** A line the shape check let through, or one read back from storage. Still checked by `init`. */
        fun constructFromPersisted(lineId: Int, quantity: Int): ReturnItem = ReturnItem(lineId, quantity)
    }
}

/**
 * The shop collects the parcel (`PickupRequest`): with [carrier], TIPAX or POST only (decision 9), maybe at
 * [scheduledFor], maybe with the carrier's [trackingCode] (the order's TrackingCode rule, decision 27). [note] is internal.
 */
@ConsistentCopyVisibility
data class ReturnPickup private constructor(
    val carrier: ShippingMethodCode,
    val scheduledFor: Instant?,
    val trackingCode: TrackingCode?,
    val note: ReturnText?,
) {
    init {
        require(carrier in ReturnPolicy.PICKUP_CARRIERS) { "A pickup by $carrier" }
    }

    override fun toString(): String = "ReturnPickup(carrier=$carrier, scheduledFor=$scheduledFor)"

    companion object {
        /** A pickup the shape check let through, or one read back from storage. Still checked by `init`. */
        fun constructFromPersisted(carrier: ShippingMethodCode, scheduledFor: Instant?, trackingCode: TrackingCode?, note: ReturnText?): ReturnPickup =
            ReturnPickup(carrier, scheduledFor, trackingCode, note)
    }
}

/**
 * What came back of one line when the parcel was opened (`AdminReturnReceiptRequest.items[]`): [received] of it, and
 * [restock] of those go back on the shelf. Each 0 to ReturnPolicy.MAX_QUANTITY (the shape check's NUMBER_OUT_OF_RANGE);
 * the rest of the rules are the return's (ReturnRequest.receive).
 */
@ConsistentCopyVisibility
data class ReceiptLine private constructor(val lineId: Int, val received: Int, val restock: Int) {
    init {
        require(lineId >= 1) { "Invalid receipt line id $lineId" }
        require(received in 0..ReturnPolicy.MAX_QUANTITY && restock in 0..ReturnPolicy.MAX_QUANTITY) { "Invalid receipt quantities $received, $restock" }
    }

    companion object {
        fun constructFromPersisted(lineId: Int, received: Int, restock: Int): ReceiptLine = ReceiptLine(lineId, received, restock)
    }
}

/**
 * The parcel was opened and checked (`AdminReturnRequest.receipt`): one line per line of the return, never more back on
 * the shelf than came. [note] is internal: the customer never sees the receipt (decision 22).
 */
@ConsistentCopyVisibility
data class ReturnReceipt private constructor(val lines: List<ReceiptLine>, val note: ReturnText?) {
    init {
        require(lines.isNotEmpty() && lines.distinctBy { it.lineId }.size == lines.size) { "A receipt with no line or a line twice" }
        require(lines.all { it.restock <= it.received }) { "A receipt restocks more than came" }
    }

    override fun toString(): String = "ReturnReceipt(lines=$lines)"

    companion object {
        /** A receipt the return let through (ReturnRequest.receive), or one read back from storage. Still checked by `init`. */
        fun constructFromPersisted(lines: List<ReceiptLine>, note: ReturnText?): ReturnReceipt = ReturnReceipt(lines, note)
    }
}
