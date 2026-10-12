package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.EventStore
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer
import com.kiva.infrastructure.persistence.exposed.projector.InventoryQmProjector

/**
 * The stock of each color kept as its events in eventstore_tbl (stream INVENTORY/{variantId}). Runs inside the use
 * case's transaction; it never opens one itself.
 *
 * [save] keeps the read model in step: InventoryQmProjector projects each event right after it is stored.
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
     * Adds the events recorded since the stock was read, each followed by its projection into the read model. If
     * someone else changed it in the meantime (two purchases of the same color at once), the save stops with
     * [ConcurrentChangeException] and none of the new events, nor their projections, stay.
     *
     * The product's row in the read model is locked first, before the stream (InventoryQmProjector.lockFor): whoever
     * waits on a color's stream then always waits at its product's row first, in the one order every writer takes.
     */
    override fun save(inventory: Inventory) {
        val events = inventory.getRecordedEvents()
        if (events.isEmpty()) return
        InventoryQmProjector.lockFor(inventory.variantId)
        events.forEach {
            EventStore.append(it, DomainEventSerializer.serialize(it))
            InventoryQmProjector.project(it)
        }
    }
}
