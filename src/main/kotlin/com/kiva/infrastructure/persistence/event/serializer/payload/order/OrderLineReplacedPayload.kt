package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderLineReplaced
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [line] in the shape OrderPlaced keeps its lines in; [priceDifference] in toman, signed. */
@Serializable
data class OrderLineReplacedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val lineId: Int,
    val line: OrderLinePayload,
    val priceDifference: Long,
) : EventPayload<OrderLineReplaced> {

    constructor(event: OrderLineReplaced) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        lineId = event.lineId,
        line = OrderLinePayload(event.line),
        priceDifference = event.priceDifference,
    )

    override fun toDomainEvent(): OrderLineReplaced = OrderLineReplaced(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        lineId = lineId,
        line = line.toDomain(),
        priceDifference = priceDifference,
        id = EventId.constructFromPersisted(id),
    )
}
