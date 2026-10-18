package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderLineRemoved
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [paidShare] in toman. */
@Serializable
data class OrderLineRemovedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val lineId: Int,
    val paidShare: Long,
) : EventPayload<OrderLineRemoved> {

    constructor(event: OrderLineRemoved) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        lineId = event.lineId,
        paidShare = event.paidShare.toman,
    )

    override fun toDomainEvent(): OrderLineRemoved = OrderLineRemoved(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        lineId = lineId,
        paidShare = Money.constructFromPersisted(paidShare),
        id = EventId.constructFromPersisted(id),
    )
}
