package com.kiva.domain.aggregate.inventory.entity.event

import com.kiva.common.event.DomainEvent
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.event.valueobject.EventVersion
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Everything that can happen to the stock of one color of a product: the warehouse book. The stream's id is the
 * variant's id (`INVENTORY/101`), so it never shares its numbers with the product's own stream.
 *
 * The `type` of each event is stored with it and read back to pick the class, so a type in use is never renamed.
 */
sealed class InventoryDomainEvent : DomainEvent {
    abstract val variantId: VariantId

    override val aggregateName: EventAggregateName get() = AGGREGATE_NAME
    override val aggregateId: EventAggregateId get() = EventAggregateId.of(variantId.value)
    override val version: EventVersion get() = EventVersion.FIRST

    companion object {
        val AGGREGATE_NAME = EventAggregateName("INVENTORY")
    }
}
