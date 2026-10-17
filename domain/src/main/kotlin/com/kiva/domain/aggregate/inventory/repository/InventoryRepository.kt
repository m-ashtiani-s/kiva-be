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

    /**
     * Holds the color's stock for a change until the caller's transaction ends, **before** it is read: another writer
     * of the same stock then waits instead of reading the same version and being refused at the save. For a writer
     * that expects others at the same moment (staff adjusting one color many times at once). A store with nothing to
     * hold (an in-memory one) does nothing.
     */
    fun holdForChange(variantId: VariantId) {}
}
