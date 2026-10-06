package com.kiva.domain.aggregate.inventory.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * Staff changed what is in the warehouse by hand: a delivery, a count, damage, a return or a correction. One event
 * for all of them, as in the contract's adjustment; [reason] tells them apart.
 */
data class StockAdjusted(
    override val variantId: VariantId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val delta: StockDelta,
    val reason: AdjustmentReason,
    val note: AdjustmentNote?,
    override val id: EventId = EventId.generate(),
) : InventoryDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("StockAdjusted")
    }
}
