package com.kiva.domain.aggregate.product.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.product.ProductTestData.newProduct
import com.kiva.domain.aggregate.product.ProductTestData.priceTag
import com.kiva.domain.aggregate.product.ProductTestData.toman
import com.kiva.domain.aggregate.product.ProductTestData.variantId
import com.kiva.domain.aggregate.product.ProductTestData.withColor
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricePolicyTest {

    @Nested
    inner class Discount {
        @Test
        fun `the artifact's example, round((1 - 1450000 divided by 1750000) x 100), is 17`() {
            assertEquals(17, PricePolicy.discountPercent(priceTag(1_450_000, compareAt = 1_750_000)))
        }

        // The six sale products of the prototype (kiva.js), with the percent its product card shows.
        @ParameterizedTest
        @CsvSource("1450000, 1750000, 17", "2790000, 3290000, 15", "1690000, 1990000, 15", "1980000, 2400000, 18", "1150000, 1390000, 17", "690000, 790000, 13")
        fun `every sale product shows the same percent as the prototype`(price: Long, compareAt: Long, percent: Int) {
            assertEquals(percent, PricePolicy.discountPercent(priceTag(price, compareAt)))
        }

        @Test
        fun `exactly half a percent rounds up, without floating-point surprises`() {
            assertEquals(18, PricePolicy.discountPercent(priceTag(1_980_000, compareAt = 2_400_000))) // exactly 17.5%
        }

        @Test
        fun `no crossed-out price means no discount`() {
            assertEquals(0, PricePolicy.discountPercent(priceTag(1_890_000)))
            assertEquals(Money.ZERO, PricePolicy.discountAmount(priceTag(1_890_000)))
            assertFalse(PricePolicy.isOnSale(priceTag(1_890_000)))
        }

        @Test
        fun `the discount amount is the crossed-out price minus the price`() {
            assertEquals(toman(300_000), PricePolicy.discountAmount(priceTag(1_450_000, compareAt = 1_750_000)))
        }

        @Test
        fun `a crossed-out price only a few toman higher rounds to 0 percent and is not shown as a sale`() {
            val almostNothing = priceTag(1_000_000, compareAt = 1_000_004)

            assertEquals(0, PricePolicy.discountPercent(almostNothing))
            assertFalse(PricePolicy.isOnSale(almostNothing))
            assertTrue(PricePolicy.isOnSale(priceTag(1_450_000, compareAt = 1_750_000)))
        }
    }

    @Nested
    inner class WhichPrice {
        @Test
        fun `a color costs its own price tag, or the product's when it has none`() {
            val product = newProduct(priceTag = priceTag(1_890_000))
                .withColor(101, "black")
                .withColor(102, "lilac", ownPriceTag = priceTag(1_590_000, compareAt = 1_890_000))

            assertEquals(priceTag(1_890_000), PricePolicy.priceTagOf(product, product.findVariant(variantId(101))!!))
            assertEquals(priceTag(1_590_000, 1_890_000), PricePolicy.priceTagOf(product, product.findVariant(variantId(102))!!))
        }

        @Test
        fun `the card shows the default color's price`() {
            val product = newProduct(priceTag = priceTag(1_890_000))
                .withColor(101, "black")
                .withColor(102, "lilac", makeDefault = true, ownPriceTag = priceTag(1_590_000))

            assertEquals(priceTag(1_590_000), PricePolicy.cardPriceTag(product))
        }

        @Test
        fun `a product without an active color shows its own price`() {
            assertEquals(priceTag(1_890_000), PricePolicy.cardPriceTag(newProduct(priceTag = priceTag(1_890_000))))
        }
    }

    @Test
    fun `free shipping starts at the threshold itself`() {
        val threshold = toman(3_000_000)

        assertTrue(PricePolicy.qualifiesForFreeShipping(priceTag(3_000_000), threshold))
        assertFalse(PricePolicy.qualifiesForFreeShipping(priceTag(2_999_999), threshold))
    }
}
