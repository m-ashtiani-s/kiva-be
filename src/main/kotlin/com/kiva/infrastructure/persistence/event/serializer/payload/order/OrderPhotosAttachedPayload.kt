package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosAttached
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OrderPhotosAttachedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val items: List<OrderPhotoPayload>,
) : EventPayload<OrderPhotosAttached> {

    constructor(event: OrderPhotosAttached) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        items = event.items.map(::OrderPhotoPayload),
    )

    override fun toDomainEvent(): OrderPhotosAttached = OrderPhotosAttached(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        items = items.map { it.toDomain() },
        id = EventId.constructFromPersisted(id),
    )
}
