package com.kiva.application.repository

import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * What the shop offers of some colors right now, read from the read model on every cart request: the cart never stores
 * a price or a stock number of its own.
 */
interface OfferQmRepository {

    /**
     * The offers of these colors in one query, whatever their product's status (a cart still shows a bag that left the
     * shop). A color nobody has is missing from the map.
     */
    fun findOffers(variantIds: Collection<VariantId>): Map<VariantId, OfferQm>
}
