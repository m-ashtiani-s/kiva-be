package com.kiva.domain.aggregate.product.repository

import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId

/**
 * Where products are kept: as their events, not as a row. Only these two, as the skill asks for event-sourced
 * aggregates; every read for the shop goes to the read model instead.
 */
interface ProductRepository {
    /** The product rebuilt from its events, or null if it has none. */
    fun get(id: ProductId): Product?

    /**
     * Appends the product's new events ([Product.getRecordedEvents]) to its stream.
     *
     * Throws ConcurrentChangeException when someone else saved a change to it after it was read, and
     * [ProductCodeTakenException] when another product took its slug or a SKU in the meantime.
     */
    fun save(product: Product)
}
