package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderChangeApplied
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [balanceDueAfter] in toman; [note] is personal: never logged ([toString]). */
@Serializable
data class OrderChangeAppliedPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val changeType: String,
    val variantId: Long?,
    val note: String?,
    val balanceDueAfter: Long,
) : EventPayload<OrderChangeApplied> {

    constructor(event: OrderChangeApplied) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        changeType = event.changeType.name,
        variantId = event.variantId?.value,
        note = event.note?.value,
        balanceDueAfter = event.balanceDueAfter.toman,
    )

    override fun toDomainEvent(): OrderChangeApplied = OrderChangeApplied(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        changeType = ChangeType.valueOf(changeType),
        variantId = variantId?.let { VariantId.constructFromPersisted(it) },
        note = note?.let { StatusNote.constructFromPersisted(it) },
        balanceDueAfter = Money.constructFromPersisted(balanceDueAfter),
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String =
        "OrderChangeAppliedPayload(orderId=$orderId, sequence=$sequence, changeType=$changeType, variantId=$variantId, balanceDueAfter=$balanceDueAfter)"
}
