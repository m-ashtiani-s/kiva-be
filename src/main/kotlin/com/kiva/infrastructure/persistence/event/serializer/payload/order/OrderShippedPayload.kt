package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderShipped
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OrderShippedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val carrier: String,
    val trackingCode: String,
    val shippedOn: String,
    val showInDailyList: Boolean,
    val reservationGroupId: Long?,
) : EventPayload<OrderShipped> {

    constructor(event: OrderShipped) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        carrier = event.carrier.name,
        trackingCode = event.trackingCode.value,
        shippedOn = event.shippedOn.toString(),
        showInDailyList = event.showInDailyList,
        reservationGroupId = event.reservationGroupId?.value,
    )

    override fun toDomainEvent(): OrderShipped = OrderShipped(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        carrier = ShippingMethodCode.valueOf(carrier),
        trackingCode = TrackingCode.constructFromPersisted(trackingCode),
        shippedOn = Instant.parse(shippedOn),
        showInDailyList = showInDailyList,
        reservationGroupId = reservationGroupId?.let { ReservationGroupId.constructFromPersisted(it) },
        id = EventId.constructFromPersisted(id),
    )
}
