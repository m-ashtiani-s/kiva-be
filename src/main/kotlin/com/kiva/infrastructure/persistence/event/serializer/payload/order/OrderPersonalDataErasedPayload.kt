package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderPersonalDataErased
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [reason] by name; nothing personal, so nothing to hide. */
@Serializable
data class OrderPersonalDataErasedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val reason: String,
) : EventPayload<OrderPersonalDataErased> {

    constructor(event: OrderPersonalDataErased) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        reason = event.reason.name,
    )

    override fun toDomainEvent(): OrderPersonalDataErased = OrderPersonalDataErased(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        reason = ErasureReason.valueOf(reason),
        id = EventId.constructFromPersisted(id),
    )
}
