package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRequested
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [lineId] and [desiredVariantId] as the change type has them; [note] is personal: never logged ([toString]). */
@Serializable
data class OrderChangeRequestedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val changeType: String,
    val lineId: Int?,
    val desiredVariantId: Long?,
    val note: String?,
) : EventPayload<OrderChangeRequested> {

    constructor(event: OrderChangeRequested) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        changeType = event.changeType.name,
        lineId = event.lineId,
        desiredVariantId = event.desiredVariantId?.value,
        note = event.note?.value,
    )

    override fun toDomainEvent(): OrderChangeRequested = OrderChangeRequested(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        changeType = ChangeType.valueOf(changeType),
        lineId = lineId,
        desiredVariantId = desiredVariantId?.let { VariantId.constructFromPersisted(it) },
        note = note?.let { StatusNote.constructFromPersisted(it) },
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String = "OrderChangeRequestedPayload(orderId=$orderId, sequence=$sequence, changeType=$changeType, lineId=$lineId)"
}
