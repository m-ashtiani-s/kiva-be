package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.PricingFixtures.date
import com.kiva.domain.aggregate.order.PricingFixtures.post
import com.kiva.domain.aggregate.order.PricingFixtures.toman
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// These values are only made by the policies, so a broken one is a bug: each refuses numbers that don't add up.
class PricingValueObjectsTest {

    @Nested
    inner class Totals {
        private fun totals(
            itemsCount: Int = 2,
            linesCount: Int = 2,
            itemsCompareAtTotal: Long = 4_080_000,
            productDiscount: Long = 600_000,
            subtotal: Long = 3_480_000,
            codeDiscount: Long = 0,
            shippingCost: Long = 0,
            shippingIsFree: Boolean = true,
            shippingFreeReason: ShippingFreeReason? = ShippingFreeReason.THRESHOLD,
            payable: Long = 3_480_000,
            discountCode: String? = "KIVA10".takeIf { codeDiscount > 0 },
        ) = OrderTotals(
            itemsCount, linesCount, toman(itemsCompareAtTotal), toman(productDiscount), toman(subtotal), toman(codeDiscount),
            toman(shippingCost), shippingIsFree, shippingFreeReason, toman(payable), discountCode?.let(DiscountCodeText::constructFromPersisted),
        )

        @Test
        fun `totals that add up are kept, and the savings are both discounts`() {
            assertEquals(toman(948_000), totals(codeDiscount = 348_000, payable = 3_132_000).totalSavings)
        }

        @Test
        fun `a code discount without its code is a bug, a code that takes 0 off is not`() {
            assertFailsWith<IllegalArgumentException> { totals(codeDiscount = 348_000, payable = 3_132_000, discountCode = null) }
            assertEquals("KIVA10", totals(discountCode = "KIVA10").discountCode?.value)
        }

        @Test
        fun `totals that don't add up are refused`() {
            assertFailsWith<IllegalArgumentException> { totals(productDiscount = 500_000) }
            assertFailsWith<IllegalArgumentException> { totals(payable = 3_480_001) }
            assertFailsWith<IllegalArgumentException> { totals(codeDiscount = 3_480_001, payable = 0) }
            assertFailsWith<IllegalArgumentException> { totals(shippingCost = 75_000, payable = 3_555_000) }
            assertFailsWith<IllegalArgumentException> { totals(shippingIsFree = false) }
            assertFailsWith<IllegalArgumentException> { totals(itemsCount = 1) }
        }
    }

    @Nested
    inner class Line {
        @Test
        fun `a line's discount is the crossed-out total minus the total`() {
            assertFailsWith<IllegalArgumentException> { LineTotals(toman(1_450_000), toman(1_750_000), toman(200_000)) }
            assertFailsWith<IllegalArgumentException> { LineTotals(toman(1_450_000), null, toman(1)) }
        }
    }

    @Nested
    inner class Quote {
        private val window = DeliveryWindow(date("2026-10-19"), date("2026-10-21"))

        @Test
        fun `a quote costs 0 or the base cost, and only a free one has a free reason`() {
            assertFailsWith<IllegalArgumentException> { ShippingQuote(post(), toman(70_000), false, null, window, null) }
            assertFailsWith<IllegalArgumentException> { ShippingQuote(post(), toman(75_000), true, null, window, null) }
            assertFailsWith<IllegalArgumentException> { ShippingQuote(post(), Money.ZERO, false, ShippingFreeReason.THRESHOLD, window, null) }
        }

        @Test
        fun `an unavailable reason makes it unavailable`() {
            assertFalse(ShippingQuote(post(), toman(75_000), false, null, window, ShippingUnavailableReason.CONSOLIDATED_WITH_RESERVATION).available)
        }

        @Test
        fun `a delivery window ends no earlier than it starts`() {
            assertFailsWith<IllegalArgumentException> { DeliveryWindow(date("2026-10-21"), date("2026-10-19")) }
        }
    }

    @Nested
    inner class Progress {
        @Test
        fun `the bar is full exactly when nothing is left, and only then`() {
            assertFailsWith<IllegalArgumentException> { FreeShippingProgress(toman(3_000_000), POST, eligible = true, remaining = toman(1), progressPerMille = 1000) }
            assertFailsWith<IllegalArgumentException> { FreeShippingProgress(toman(3_000_000), POST, eligible = false, remaining = toman(1), progressPerMille = 1000) }
            assertFailsWith<IllegalArgumentException> { FreeShippingProgress(toman(3_000_000), POST, eligible = false, remaining = toman(1), progressPerMille = 1001) }
            assertFailsWith<IllegalArgumentException> { FreeShippingProgress(toman(3_000_000), POST, eligible = false, remaining = toman(1), progressPerMille = -1) }
        }
    }
}
