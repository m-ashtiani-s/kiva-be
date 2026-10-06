package com.kiva.domain.aggregate.product.model.policy

import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus

/**
 * What a shopper may know about the stock of one color. The exact number stays inside the shop: it is shown only
 * when few are left («فقط ۳ عدد»), as the contract asks.
 */
object StockPolicy {

    /** From the contract (`lowStockThreshold`, default 5): fewer than this many left is LOW_STOCK. */
    const val LOW_STOCK_THRESHOLD = 5

    /**
     * @param available what can still be sold (in stock minus reserved). Inventory never lets it go below 0,
     *   so a negative number is a bug.
     */
    fun stockOf(available: Int, maxPerOrder: MaxPerOrder): StockInfo {
        require(available >= 0) { "Available stock can't be negative: $available" }

        val status = when {
            available == 0 -> StockStatus.OUT_OF_STOCK
            available < LOW_STOCK_THRESHOLD -> StockStatus.LOW_STOCK
            else -> StockStatus.IN_STOCK
        }
        return StockInfo(
            status = status,
            shownQuantity = available.takeIf { status == StockStatus.LOW_STOCK },
            maxOrderQuantity = minOf(available, maxPerOrder.value),
        )
    }
}
