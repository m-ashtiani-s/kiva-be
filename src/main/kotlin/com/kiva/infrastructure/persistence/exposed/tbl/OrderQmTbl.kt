package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.persistence.event.serializer.payload.order.AddressSnapshotPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.GiftNotePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderLinePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPhotoPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderTotalsPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.PreShipmentMessengerPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * One row per order, ready for the customer's lists and pages, the panel's queue and the jobs. Written only by
 * OrderQmProjector. Created by V15__orders.sql and grown by V26__order_admin_read_model.sql, V29__balance_payments.sql
 * and V30__admin_settings.sql (Exposed never creates tables); keep them in step. [lines] are the lines now (after a change request); [totals] the
 * placed ones.
 */
object OrderQmTbl : LongIdTable(name = "orderqm_tbl", columnName = "pId") {
    val orderId = long("orderId").uniqueIndex()
    val code = varchar("code", 11).uniqueIndex()
    val userId = long("userId")
    val status = enumerationByName<OrderStatus>("status", 20)
    val placedOn = timestampWithTimeZone("placedOn")
    val paidOn = timestampWithTimeZone("paidOn").nullable()
    val holdUntil = timestampWithTimeZone("holdUntil")
    val payable = long("payable")
    val itemsCount = integer("itemsCount")
    val shippingMethod = enumerationByName<ShippingMethodCode>("shippingMethod", 10)
    val messengerChannel = enumerationByName<MessengerChannel>("messengerChannel", 10)
    val reserveRequested = bool("reserveRequested")
    // V19: the reservation group the order ships with (joined when placed, or held in when paid).
    val reservationGroupId = long("reservationGroupId").nullable()
    val lines = jsonb<List<OrderLinePayload>>("lines", Json)
    val address = jsonb<AddressSnapshotPayload>("address", Json)
    val messenger = jsonb<PreShipmentMessengerPayload>("messenger", Json)
    val totals = jsonb<OrderTotalsPayload>("totals", Json)
    val timeline = jsonb<List<OrderTimelineEntry>>("timeline", Json)
    val gift = jsonb<GiftNotePayload>("gift", Json).nullable()
    val customerNote = varchar("customerNote", 300).nullable()
    val updatedOn = timestampWithTimeZone("updatedOn")

    // ---- V26: after the payment ----
    val searchText = text("searchText")
    val photoStatus = enumerationByName<PhotoStatus>("photoStatus", 16)
    val photoEverSent = bool("photoEverSent")
    val photos = jsonb<OrderPhotosQm>("photos", Json)
    val photosSentOn = timestampWithTimeZone("photosSentOn").nullable()
    val feedbackDeadline = timestampWithTimeZone("feedbackDeadline").nullable()
    val feedback = jsonb<OrderFeedbackQm>("feedback", Json).nullable()
    val trackingCode = varchar("trackingCode", 40).nullable()
    val shippedOn = timestampWithTimeZone("shippedOn").nullable()
    val showInDailyList = bool("showInDailyList")
    val deliveredOn = timestampWithTimeZone("deliveredOn").nullable()
    val returnableUntil = timestampWithTimeZone("returnableUntil").nullable()
    val cancelledOn = timestampWithTimeZone("cancelledOn").nullable()
    val cancellation = jsonb<OrderCancellationQm>("cancellation", Json).nullable()
    val refunded = long("refunded")

    // ---- V29: the money after a change request ----
    val balanceDue = long("balanceDue")
    val balancePaid = long("balancePaid")
    val codeDiscountRemoved = long("codeDiscountRemoved")

    // ---- V30: what the panel's report counts ----
    /** When the first photos were sent (the first OrderPhotosSent); later batches leave it. */
    val firstPhotosSentOn = timestampWithTimeZone("firstPhotosSentOn").nullable()
    /** The customer ever asked for a change, whatever came of it. */
    val changeRequestedEver = bool("changeRequestedEver")
}

/**
 * One entry of an order's timeline, as the projector writes it for each event the customer sees: what happened ([kind]),
 * the status the order was in after it, and when. The Persian label is the HTTP layer's, made from the kind.
 *
 * @property failureReason why an attempt to pay failed.
 * @property kind an OrderTimelineKind; every row has one (V26 gave the rows before it theirs).
 * @property note staff's or the customer's note of a cancellation or a delivery, shown as it was written.
 * @property amount money given back, in toman, for a REFUNDED entry.
 */
@Serializable
data class OrderTimelineEntry(
    val status: String,
    val at: String,
    val failureReason: String?,
    val kind: String,
    val note: String? = null,
    val amount: Long? = null,
)

/** The photos before shipping: the operator's draft (the panel's only), the last batch sent, and how many were sent. */
@Serializable
data class OrderPhotosQm(val draft: List<OrderPhotoPayload>, val sent: List<OrderPhotoPayload>, val batch: Int) {
    companion object {
        val NONE = OrderPhotosQm(emptyList(), emptyList(), 0)
    }
}

/**
 * The customer's last answer to the photos (`PreShipmentMedia.feedback`): «همونه!» (APPROVE), or a change request with
 * what was asked and, once staff resolved it, how ([resolution]). Rows written before phase 3 have a null resolution,
 * which reads back the same. [note] is personal: never logged.
 */
@Serializable
data class OrderFeedbackQm(
    val decision: String,
    val changeType: String?,
    val orderItemId: Int?,
    val desiredVariantId: Long?,
    val note: String?,
    val at: String,
    val resolution: OrderResolutionQm?,
) {
    override fun toString(): String = "OrderFeedbackQm(decision=$decision, changeType=$changeType, at=$at)"
}

/** How staff resolved a change request: APPLY (with the color put in, for COLOR and MODEL) or REJECT; [note] is personal. */
@Serializable
data class OrderResolutionQm(val decision: String, val variantId: Long?, val note: String?, val at: String) {
    override fun toString(): String = "OrderResolutionQm(decision=$decision, variantId=$variantId, at=$at)"
}

/** How the order was cancelled; the note is personal. */
@Serializable
data class OrderCancellationQm(val by: String, val reason: String?, val note: String?) {
    override fun toString(): String = "OrderCancellationQm(by=$by, reason=$reason)"
}
