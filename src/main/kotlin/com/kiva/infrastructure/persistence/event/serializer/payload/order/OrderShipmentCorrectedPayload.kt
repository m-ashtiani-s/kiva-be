package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderShipmentCorrected
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OrderShipmentCorrectedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val trackingCode: String,
    val shippedOn: String,
    val showInDailyList: Boolean,
) : EventPayload<OrderShipmentCorrected> {

    constructor(event: OrderShipmentCorrected) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        trackingCode = event.trackingCode.value,
        shippedOn = event.shippedOn.toString(),
        showInDailyList = event.showInDailyList,
    )

    override fun toDomainEvent(): OrderShipmentCorrected = OrderShipmentCorrected(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        trackingCode = TrackingCode.constructFromPersisted(trackingCode),
        shippedOn = Instant.parse(shippedOn),
        showInDailyList = showInDailyList,
        id = EventId.constructFromPersisted(id),
    )
}
