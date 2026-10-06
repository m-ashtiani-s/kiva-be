package com.kiva.domain.aggregate.inventory.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/** A paid order was promised [quantity] of the bags in the warehouse; they stay there until it ships. */
data class StockReserved(
    override val variantId: VariantId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val orderCode: OrderCode,
    val quantity: Quantity,
    override val id: EventId = EventId.generate(),
) : InventoryDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("StockReserved")
    }
}
