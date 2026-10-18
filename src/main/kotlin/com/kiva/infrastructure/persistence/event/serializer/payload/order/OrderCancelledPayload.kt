package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderCancelled
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [note] is personal: never logged ([toString]). */
@Serializable
data class OrderCancelledPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val by: String,
    val reason: String?,
    val note: String?,
) : EventPayload<OrderCancelled> {

    constructor(event: OrderCancelled) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        by = event.by.name,
        reason = event.reason?.name,
        note = event.note?.value,
    )

    override fun toDomainEvent(): OrderCancelled = OrderCancelled(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        by = CancelledBy.valueOf(by),
        reason = reason?.let(CancelReason::valueOf),
        note = note?.let { StatusNote.constructFromPersisted(it) },
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String = "OrderCancelledPayload(orderId=$orderId, sequence=$sequence, by=$by, reason=$reason)"
}
