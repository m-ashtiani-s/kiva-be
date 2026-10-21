package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderReturnEnded
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [outcome] by name. */
@Serializable
data class OrderReturnEndedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val returnId: Long,
    val outcome: String,
) : EventPayload<OrderReturnEnded> {

    constructor(event: OrderReturnEnded) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        returnId = event.returnId.value,
        outcome = event.outcome.name,
    )

    override fun toDomainEvent(): OrderReturnEnded = OrderReturnEnded(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        returnId = ReturnId.constructFromPersisted(returnId),
        outcome = ReturnOutcome.valueOf(outcome),
        id = EventId.constructFromPersisted(id),
    )
}
