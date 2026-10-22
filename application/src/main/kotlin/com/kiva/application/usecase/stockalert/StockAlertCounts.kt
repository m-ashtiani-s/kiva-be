package com.kiva.application.usecase.stockalert

import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository

/**
 * How many customers wait for each color (`AdminVariant.stockAlertSubscribers`; roadmap section 15.2, decision 17): the
 * ACTIVE subscriptions of that very color, «any color» ones left out; one read for all a product's colors.
 */
fun interface StockAlertCounts {
    fun of(variantIds: Collection<VariantId>): Map<VariantId, Int>

    companion object {
        /** For what reads no subscription: nobody waits. */
        val NONE = StockAlertCounts { emptyMap() }

        fun of(stockAlertRepository: StockAlertRepository) = StockAlertCounts(stockAlertRepository::subscribersOf)
    }
}
