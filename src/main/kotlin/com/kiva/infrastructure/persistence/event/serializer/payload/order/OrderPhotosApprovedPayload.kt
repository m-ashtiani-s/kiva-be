package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosApproved
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OrderPhotosApprovedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val automatic: Boolean,
) : EventPayload<OrderPhotosApproved> {

    constructor(event: OrderPhotosApproved) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        automatic = event.automatic,
    )

    override fun toDomainEvent(): OrderPhotosApproved = OrderPhotosApproved(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        automatic = automatic,
        id = EventId.constructFromPersisted(id),
    )
}
