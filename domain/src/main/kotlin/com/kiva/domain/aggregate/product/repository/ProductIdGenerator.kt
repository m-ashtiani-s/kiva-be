package com.kiva.domain.aggregate.product.repository

import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Hands out new numeric ids, each only once, even to two requests at the same moment.
 *
 * A product has no row before its first event, so its id must exist before it is created. The real one reads a
 * database sequence (step 3); "the largest id + 1" would give two requests at the same moment the same number.
 * Here is only the contract, so the domain doesn't know where the numbers come from.
 */
interface ProductIdGenerator {
    fun nextProductId(): ProductId

    fun nextVariantId(): VariantId
}
