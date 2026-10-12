package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** The whole order as it was placed. It holds the customer's address and numbers: never printed whole. */
@Serializable
data class OrderPlacedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val code: String,
    val userId: Long,
    val lines: List<OrderLinePayload>,
    val address: AddressSnapshotPayload,
    val messenger: PreShipmentMessengerPayload,
    val shippingMethod: String,
    val totals: OrderTotalsPayload,
    val reserveRequested: Boolean,
    val holdUntil: String,
    val customerNote: String?,
    val gift: GiftNotePayload?,
) : EventPayload<OrderPlaced> {

    constructor(event: OrderPlaced) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        code = event.code.value,
        userId = event.userId.value,
        lines = event.lines.map(::OrderLinePayload),
        address = AddressSnapshotPayload(event.address),
        messenger = PreShipmentMessengerPayload(event.messenger),
        shippingMethod = event.shippingMethod.name,
        totals = OrderTotalsPayload(event.totals),
        reserveRequested = event.reserveRequested,
        holdUntil = event.holdUntil.toString(),
        customerNote = event.customerNote?.value,
        gift = event.gift?.let(::GiftNotePayload),
    )

    override fun toDomainEvent(): OrderPlaced = OrderPlaced(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        code = OrderCode.constructFromPersisted(code),
        userId = UserId.constructFromPersisted(userId),
        lines = lines.map { it.toDomain() },
        address = address.toDomain(),
        messenger = messenger.toDomain(),
        shippingMethod = ShippingMethodCode.valueOf(shippingMethod),
        totals = totals.toDomain(),
        reserveRequested = reserveRequested,
        holdUntil = Instant.parse(holdUntil),
        customerNote = customerNote?.let { CustomerNote.constructFromPersisted(it) },
        gift = gift?.toDomain(),
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String = "OrderPlacedPayload(orderId=$orderId, code=$code, sequence=$sequence)"
}
