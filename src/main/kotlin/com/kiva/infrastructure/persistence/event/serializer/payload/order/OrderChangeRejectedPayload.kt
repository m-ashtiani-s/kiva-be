package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRejected
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [note] is personal: never logged ([toString]). */
@Serializable
data class OrderChangeRejectedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val note: String,
) : EventPayload<OrderChangeRejected> {

    constructor(event: OrderChangeRejected) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        note = event.note.value,
    )

    override fun toDomainEvent(): OrderChangeRejected = OrderChangeRejected(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        note = StatusNote.constructFromPersisted(note),
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String = "OrderChangeRejectedPayload(orderId=$orderId, sequence=$sequence)"
}
