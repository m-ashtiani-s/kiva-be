package com.kiva.domain.aggregate.product.model.policy

import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus

/**
 * What a shopper may know about the stock of one color. The exact number stays inside the shop: it is shown only
 * when few are left («فقط ۳ عدد»), as the contract asks.
 *
 * How few is the shop's setting (`ShopSettings.lowStockThreshold`, 5 to begin with), read on every request, so a change
 * shows everywhere at once (roadmap phase 4, decision 5).
 */
object StockPolicy {

    /**
     * @param available what can still be sold (in stock minus reserved). Inventory never lets it go below 0,
     *   so a negative number is a bug.
     * @param lowStockThreshold fewer than this many left is LOW_STOCK.
     */
    fun stockOf(available: Int, maxPerOrder: MaxPerOrder, lowStockThreshold: Int): StockInfo {
        require(available >= 0) { "Available stock can't be negative: $available" }
        require(lowStockThreshold > 0) { "A low stock threshold must be positive: $lowStockThreshold" }

        val status = when {
            available == 0 -> StockStatus.OUT_OF_STOCK
            available < lowStockThreshold -> StockStatus.LOW_STOCK
            else -> StockStatus.IN_STOCK
        }
        return StockInfo(
            status = status,
            shownQuantity = available.takeIf { status == StockStatus.LOW_STOCK },
            maxOrderQuantity = minOf(available, maxPerOrder.value),
        )
    }
}
