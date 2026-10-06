package com.kiva.infrastructure.persistence.event.serializer.payload.inventory

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class StockAdjustedPayload(
    val id: String,
    val variantId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val delta: Int,
    val reason: String,
    val note: String?,
) : EventPayload<StockAdjusted> {

    constructor(event: StockAdjusted) : this(
        id = event.id.value,
        variantId = event.variantId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        delta = event.delta.value,
        reason = event.reason.name,
        note = event.note?.value,
    )

    override fun toDomainEvent(): StockAdjusted = StockAdjusted(
        variantId = VariantId.constructFromPersisted(variantId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        delta = StockDelta.constructFromPersisted(delta),
        reason = AdjustmentReason.valueOf(reason),
        note = note?.let { AdjustmentNote.constructFromPersisted(it) },
        id = EventId.constructFromPersisted(id),
    )
}
