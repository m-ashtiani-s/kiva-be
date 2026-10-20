package com.kiva.domain.aggregate.product.model.policy

import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus.IN_STOCK
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus.LOW_STOCK
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus.OUT_OF_STOCK
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StockPolicyTest {

    private val five = MaxPerOrder.DEFAULT

    @Test
    fun `nothing left is OUT_OF_STOCK, and nothing can go into a cart`() {
        assertEquals(StockInfo(OUT_OF_STOCK, shownQuantity = null, maxOrderQuantity = 0), StockPolicy.stockOf(0, five, lowStockThreshold = 5))
    }

    @Test
    fun `1 to 4 left is LOW_STOCK, and only then the number is shown`() {
        assertEquals(StockInfo(LOW_STOCK, shownQuantity = 1, maxOrderQuantity = 1), StockPolicy.stockOf(1, five, lowStockThreshold = 5))
        assertEquals(StockInfo(LOW_STOCK, shownQuantity = 4, maxOrderQuantity = 4), StockPolicy.stockOf(4, five, lowStockThreshold = 5))
    }

    @Test
    fun `5 or more is IN_STOCK, and the exact number stays hidden (fewer than 5 is low, as the contract says)`() {
        assertEquals(StockInfo(IN_STOCK, shownQuantity = null, maxOrderQuantity = 5), StockPolicy.stockOf(5, five, lowStockThreshold = 5))
        assertEquals(StockInfo(IN_STOCK, shownQuantity = null, maxOrderQuantity = 5), StockPolicy.stockOf(12, five, lowStockThreshold = 5))
    }

    @Test
    fun `a cart may hold what is left, but no more than the product's limit per order`() {
        val two = MaxPerOrder.constructFromPersisted(2)

        assertEquals(2, StockPolicy.stockOf(30, two, lowStockThreshold = 5).maxOrderQuantity)
        assertEquals(3, StockPolicy.stockOf(3, five, lowStockThreshold = 5).maxOrderQuantity)
    }

    @Test
    fun `the shop's own threshold decides what is low - with 3, four left is IN_STOCK and two is LOW_STOCK`() {
        assertEquals(IN_STOCK, StockPolicy.stockOf(4, five, lowStockThreshold = 3).status)
        assertEquals(StockInfo(LOW_STOCK, shownQuantity = 2, maxOrderQuantity = 2), StockPolicy.stockOf(2, five, lowStockThreshold = 3))
        assertEquals(LOW_STOCK, StockPolicy.stockOf(19, five, lowStockThreshold = 20).status)
    }

    @Test
    fun `negative stock is a bug, because Inventory never lets it happen`() {
        assertFailsWith<IllegalArgumentException> { StockPolicy.stockOf(-1, five, lowStockThreshold = 5) }
    }
}
