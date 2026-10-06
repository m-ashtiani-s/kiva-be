package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.product.ProductTestData.toman
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PriceTagTest {

    @Test
    fun `a price without a crossed-out price is a price that is not on sale`() {
        val tag = PriceTag.makeNew(toman(1_890_000), null).getOrNull()!!

        assertEquals(toman(1_890_000), tag.price)
        assertNull(tag.compareAtPrice)
    }

    @Test
    fun `a crossed-out price above the price is a sale`() {
        val tag = PriceTag.makeNew(toman(1_450_000), toman(1_750_000)).getOrNull()!!

        assertEquals(toman(1_750_000), tag.compareAtPrice)
    }

    @Test
    fun `a crossed-out price equal to or below the price is COMPARE_AT_PRICE_NOT_ABOVE_PRICE on the compareAtPrice field`() {
        assertEquals(Result.failure(PriceTag.Failure.CompareAtNotAbovePrice), PriceTag.makeNew(toman(1_450_000), toman(1_450_000)))
        assertEquals(Result.failure(PriceTag.Failure.CompareAtNotAbovePrice), PriceTag.makeNew(toman(1_450_000), toman(1_000_000)))
        assertEquals("compareAtPrice", PriceTag.Failure.CompareAtNotAbovePrice.field)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { PriceTag.constructFromPersisted(toman(1_450_000), toman(1_450_000)) }
    }
}
