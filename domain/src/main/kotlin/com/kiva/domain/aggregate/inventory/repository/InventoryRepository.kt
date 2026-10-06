package com.kiva.domain.aggregate.inventory.repository

import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Where stock is kept: as its events, like products. Only these two, as the skill asks for event-sourced aggregates;
 * the shop reads stock from the read model instead.
 */
interface InventoryRepository {
    /** The stock of a color rebuilt from its events, or null if nothing was ever recorded for it. */
    fun get(variantId: VariantId): Inventory?

    /** Appends the new events ([Inventory.getRecordedEvents]) to the color's stream. */
    fun save(inventory: Inventory)
}
