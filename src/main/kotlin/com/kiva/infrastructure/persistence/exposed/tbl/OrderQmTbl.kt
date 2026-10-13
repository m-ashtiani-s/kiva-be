package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.persistence.event.serializer.payload.order.AddressSnapshotPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.GiftNotePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderLinePayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderTotalsPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.PreShipmentMessengerPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * One row per order, ready for the customer's lists and pages and for the jobs. Written only by OrderQmProjector.
 * Created by V15__orders.sql (Exposed never creates tables); keep both in step.
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
}

/**
 * One entry of an order's timeline, as the projector writes it for each event: the status the order went to and
 * when, and for a failed payment attempt its reason. The Persian label is the HTTP layer's.
 */
@Serializable
data class OrderTimelineEntry(
    val status: String,
    val at: String,
    val failureReason: String?,
)
