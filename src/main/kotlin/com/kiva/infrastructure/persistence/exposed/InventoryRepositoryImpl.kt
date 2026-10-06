package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.EventStore
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer

/**
 * The stock of each color kept as its events in eventstore_tbl (stream INVENTORY/{variantId}). Runs inside the use
 * case's transaction; it never opens one itself.
 */
class InventoryRepositoryImpl : InventoryRepository {

    override fun get(variantId: VariantId): Inventory? {
        val events = EventStore.readStream(
            InventoryDomainEvent.AGGREGATE_NAME,
            EventAggregateId.of(variantId.value),
            DomainEventSerializer::deserializeInventory,
        )
        return if (events.isEmpty()) null else Inventory(events)
    }

    /**
     * Adds the events recorded since the stock was read. If someone else changed it in the meantime (two purchases of
     * the same color at once), the save stops with [ConcurrentChangeException] and none of the new events stay.
     */
    override fun save(inventory: Inventory) {
        inventory.getRecordedEvents().forEach { EventStore.append(it, DomainEventSerializer.serialize(it)) }
    }
}
