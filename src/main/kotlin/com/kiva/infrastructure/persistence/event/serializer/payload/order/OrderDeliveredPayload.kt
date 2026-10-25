package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderDelivered
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [note] is staff's, shown to the customer: never logged ([toString]). */
@Serializable
data class OrderDeliveredPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val returnableUntil: String?,
    val note: String?,
) : EventPayload<OrderDelivered> {

    constructor(event: OrderDelivered) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        returnableUntil = event.returnableUntil?.toString(),
        note = event.note?.value,
    )

    override fun toDomainEvent(): OrderDelivered = OrderDelivered(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        returnableUntil = returnableUntil?.let(Instant::parse),
        note = note?.let { StatusNote.constructFromPersisted(it) },
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String = "OrderDeliveredPayload(orderId=$orderId, sequence=$sequence, returnableUntil=$returnableUntil)"
}
