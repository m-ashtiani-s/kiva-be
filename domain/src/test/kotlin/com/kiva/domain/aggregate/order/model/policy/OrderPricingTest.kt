package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.discount.DiscountFixtures
import com.kiva.domain.aggregate.order.PricingFixtures.aidaCaramel
import com.kiva.domain.aggregate.order.PricingFixtures.date
import com.kiva.domain.aggregate.order.PricingFixtures.fridayJustAfterMidnight
import com.kiva.domain.aggregate.order.PricingFixtures.kv1015Brown
import com.kiva.domain.aggregate.order.PricingFixtures.line
import com.kiva.domain.aggregate.order.PricingFixtures.nila
import com.kiva.domain.aggregate.order.PricingFixtures.post
import com.kiva.domain.aggregate.order.PricingFixtures.shopSettings
import com.kiva.domain.aggregate.order.PricingFixtures.sundayMorning
import com.kiva.domain.aggregate.order.PricingFixtures.thursdayNight
import com.kiva.domain.aggregate.order.PricingFixtures.tipax
import com.kiva.domain.aggregate.order.PricingFixtures.toman
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.PurchaseQuote
import com.kiva.domain.aggregate.order.model.valueobject.DeliveryWindow
import com.kiva.domain.aggregate.order.model.valueobject.LineTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.COURIER
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.TIPAX
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderPricingTest {

    private fun quote(
        lines: List<PricedLine>,
        method: ShippingMethodCode = POST,
        settings: ShopSettings = shopSettings(),
        now: Instant = thursdayNight,
        reserve: Boolean = false,
        codeDiscount: Long? = null,
        consolidation: ShippingConsolidation? = null,
    ): PurchaseQuote = OrderPricing.quote(lines, settings, method, now, reserve, codeDiscount?.let(DiscountFixtures::codeDiscount), consolidation)

    // Roadmap section 7.5: the same numbers as the contract's examples and the frontend's sample orders.
    @Nested
    inner class GoldenExamples {
        @Test
        fun `Aida caramel and KV-1015 brown by post - 4,080,000 less 600,000 is 3,480,000, and post is free`() {
            val totals = quote(listOf(aidaCaramel, kv1015Brown), POST).totals

            assertEquals(toman(4_080_000), totals.itemsCompareAtTotal)
            assertEquals(toman(600_000), totals.productDiscount)
            assertEquals(toman(3_480_000), totals.subtotal)
            assertEquals(Money.ZERO, totals.shippingCost)
            assertTrue(totals.shippingIsFree)
            assertEquals(ShippingFreeReason.THRESHOLD, totals.shippingFreeReason)
            assertEquals(toman(3_480_000), totals.payable)
            assertEquals(toman(600_000), totals.totalSavings)
        }

        @Test
        fun `the same by tipax - 3,625,000`() {
            val totals = quote(listOf(aidaCaramel, kv1015Brown), TIPAX).totals

            assertEquals(toman(4_080_000), totals.itemsCompareAtTotal)
            assertEquals(toman(600_000), totals.productDiscount)
            assertEquals(toman(3_480_000), totals.subtotal)
            assertEquals(toman(145_000), totals.shippingCost)
            assertFalse(totals.shippingIsFree)
            assertNull(totals.shippingFreeReason)
            assertEquals(toman(3_625_000), totals.payable)
        }

        @Test
        fun `Nila crossbody by post - 1,525,000, and 1,550,000 or 48 point 3 percent short of free shipping`() {
            val quote = quote(listOf(nila), POST)

            assertEquals(toman(1_750_000), quote.totals.itemsCompareAtTotal)
            assertEquals(toman(300_000), quote.totals.productDiscount)
            assertEquals(toman(1_450_000), quote.totals.subtotal)
            assertEquals(toman(75_000), quote.totals.shippingCost)
            assertEquals(toman(1_525_000), quote.totals.payable)
            assertEquals(toman(1_550_000), quote.freeShipping?.remaining)
            assertEquals(483, quote.freeShipping?.progressPerMille)
        }

        @Test
        fun `the first cart with KIVA10 by post - the code takes 348,000, and post is still free because the threshold looks before the code`() {
            val totals = quote(listOf(aidaCaramel, kv1015Brown), POST, codeDiscount = 348_000).totals

            assertEquals(toman(4_080_000), totals.itemsCompareAtTotal)
            assertEquals(toman(600_000), totals.productDiscount)
            assertEquals(toman(3_480_000), totals.subtotal)
            assertEquals(toman(348_000), totals.codeDiscount)
            assertEquals("KIVA10", totals.discountCode?.value)
            assertEquals(Money.ZERO, totals.shippingCost)
            assertEquals(toman(3_132_000), totals.payable)
            assertEquals(toman(948_000), totals.totalSavings)
        }
    }

    @Nested
    inner class Lines {
        @Test
        fun `a line on sale - total, crossed-out total and discount, times the quantity`() {
            assertEquals(
                LineTotals(total = toman(2_900_000), compareAtTotal = toman(3_500_000), discount = toman(600_000)),
                OrderPricing.lineTotalsOf(line(1_450_000, compareAt = 1_750_000, quantity = 2)),
            )
        }

        @Test
        fun `a line not on sale has no crossed-out total and no discount`() {
            assertEquals(
                LineTotals(total = toman(1_890_000), compareAtTotal = null, discount = Money.ZERO),
                OrderPricing.lineTotalsOf(line(1_890_000)),
            )
        }

        @Test
        fun `the quote gives each line its amounts, in the order of the lines, bought or not`() {
            val soldOut = line(1_890_000, purchasable = false)

            val quote = quote(listOf(nila, soldOut))

            assertEquals(listOf(OrderPricing.lineTotalsOf(nila), OrderPricing.lineTotalsOf(soldOut)), quote.lines)
        }
    }

    @Nested
    inner class LinesThatCantBeBought {
        @Test
        fun `stay in the counts but not in the money`() {
            val totals = quote(listOf(nila, line(1_890_000, quantity = 2, purchasable = false))).totals

            assertEquals(3, totals.itemsCount)
            assertEquals(2, totals.linesCount)
            assertEquals(toman(1_450_000), totals.subtotal)
            assertEquals(toman(1_525_000), totals.payable)
        }

        @Test
        fun `a cart with nothing to buy costs 0 everywhere and does not ship «free»`() {
            val quote = quote(listOf(line(1_890_000, purchasable = false), line(2_790_000, compareAt = 3_290_000, purchasable = false)))
            val totals = quote.totals

            assertEquals(2, totals.itemsCount)
            assertEquals(2, totals.linesCount)
            listOf(totals.itemsCompareAtTotal, totals.productDiscount, totals.subtotal, totals.codeDiscount, totals.shippingCost, totals.payable, totals.totalSavings)
                .forEach { assertEquals(Money.ZERO, it) }
            assertFalse(totals.shippingIsFree)
            assertNull(totals.shippingFreeReason)
            assertTrue(quote.shippingOptions.all { it.cost == Money.ZERO && !it.isFree })
        }

        @Test
        fun `an empty cart is the same, with nothing counted`() {
            val totals = quote(emptyList()).totals

            assertEquals(0, totals.itemsCount)
            assertEquals(0, totals.linesCount)
            assertEquals(Money.ZERO, totals.payable)
            assertFalse(totals.shippingIsFree)
        }
    }

    @Nested
    inner class FreeShippingThreshold {
        @Test
        fun `a subtotal of exactly 3,000,000 ships free by post`() {
            val totals = quote(listOf(line(3_000_000)), POST).totals

            assertEquals(Money.ZERO, totals.shippingCost)
            assertTrue(totals.shippingIsFree)
            assertEquals(toman(3_000_000), totals.payable)
        }

        @Test
        fun `2,999,000 pays for post`() {
            val totals = quote(listOf(line(2_999_000)), POST).totals

            assertEquals(toman(75_000), totals.shippingCost)
            assertFalse(totals.shippingIsFree)
            assertEquals(toman(3_074_000), totals.payable)
        }

        @Test
        fun `the threshold looks at the price after the product discount, not the crossed-out one`() {
            // 3,290,000 crossed out, 2,790,000 paid: below 3,000,000.
            assertEquals(toman(75_000), quote(listOf(aidaCaramel), POST).totals.shippingCost)
        }

        @Test
        fun `tipax is never free`() {
            assertEquals(toman(145_000), quote(listOf(line(9_000_000)), TIPAX).totals.shippingCost)
        }
    }

    @Nested
    inner class ShippingChoice {
        @Test
        fun `the options and the selected one come with the totals`() {
            val quote = quote(listOf(nila), TIPAX)

            assertEquals(listOf(TIPAX, POST), quote.shippingOptions.map { it.method.code })
            assertEquals(TIPAX, quote.shipping?.method?.code)
            assertEquals(quote.shipping?.cost, quote.totals.shippingCost)
        }

        @Test
        fun `a method the shop doesn't offer is priced as the shop's default`() {
            val quote = quote(listOf(nila), COURIER)

            assertEquals(POST, quote.shipping?.method?.code)
            assertEquals(toman(75_000), quote.totals.shippingCost)
        }

        @Test
        fun `with every method switched off, nothing is selected and shipping costs nothing yet`() {
            val quote = quote(listOf(nila), POST, shopSettings(listOf(tipax(isActive = false), post(isActive = false))))

            assertNull(quote.shipping)
            assertEquals(Money.ZERO, quote.totals.shippingCost)
            assertFalse(quote.totals.shippingIsFree)
            assertEquals(toman(1_450_000), quote.totals.payable)
        }
    }

    @Nested
    inner class DeliveryDates {
        @Test
        fun `Thursday 23 59 in Tehran - post 2026-10-19 to 2026-10-21, tipax 2026-10-17 to 2026-10-18`() {
            val options = quote(listOf(nila), now = thursdayNight).shippingOptions.associateBy { it.method.code }

            assertEquals(DeliveryWindow(date("2026-10-19"), date("2026-10-21")), options.getValue(POST).delivery)
            assertEquals(DeliveryWindow(date("2026-10-17"), date("2026-10-18")), options.getValue(TIPAX).delivery)
        }

        @Test
        fun `21 00 UTC is Friday in Tehran - it ships Saturday, so post is 2026-10-20 to 2026-10-22, not Thursday's dates`() {
            val options = quote(listOf(nila), now = fridayJustAfterMidnight).shippingOptions.associateBy { it.method.code }

            assertEquals(DeliveryWindow(date("2026-10-20"), date("2026-10-22")), options.getValue(POST).delivery)
            assertEquals(DeliveryWindow(date("2026-10-18"), date("2026-10-19")), options.getValue(TIPAX).delivery)
        }

        @Test
        fun `with the reservation on, today 2026-10-11 and a hold of 4 days, it ships 2026-10-15 and arrives from there`() {
            val options = quote(listOf(nila), now = sundayMorning, reserve = true).shippingOptions.associateBy { it.method.code }

            assertEquals(DeliveryWindow(date("2026-10-19"), date("2026-10-21")), options.getValue(POST).delivery)
            assertEquals(DeliveryWindow(date("2026-10-17"), date("2026-10-18")), options.getValue(TIPAX).delivery)
        }

        @Test
        fun `without the reservation the same day ships at once`() {
            // Sunday 2026-10-11 + 3 to 5 working days: 2026-10-14 to 2026-10-17 (Friday skipped).
            val post = quote(listOf(nila), now = sundayMorning).shippingOptions.single { it.method.code == POST }

            assertEquals(DeliveryWindow(date("2026-10-14"), date("2026-10-17")), post.delivery)
        }

        @Test
        fun `joining a reservation ignores the switch`() {
            val joined = quote(listOf(nila), now = sundayMorning, reserve = true, consolidation = ShippingConsolidation(POST))
            val notReserved = quote(listOf(nila), now = sundayMorning, reserve = false, consolidation = ShippingConsolidation(POST))

            assertEquals(notReserved.shippingOptions.map { it.delivery }, joined.shippingOptions.map { it.delivery })
        }
    }

    @Nested
    inner class RoundTwoInputs {
        @Test
        fun `joining a reservation ships free by the group's method, below the threshold too`() {
            val quote = quote(listOf(nila), TIPAX, consolidation = ShippingConsolidation(POST))

            assertEquals(POST, quote.shipping?.method?.code)
            assertEquals(Money.ZERO, quote.totals.shippingCost)
            assertEquals(ShippingFreeReason.RESERVATION_CONSOLIDATION, quote.totals.shippingFreeReason)
            assertEquals(toman(1_450_000), quote.totals.payable)
            assertEquals(true, quote.freeShipping?.eligible)
        }

        @Test
        fun `a code that takes the whole subtotal leaves only the shipping to pay`() {
            val totals = quote(listOf(nila), POST, codeDiscount = 1_450_000).totals

            assertEquals(toman(75_000), totals.payable)
        }

        @Test
        fun `a code worth more than the subtotal is a bug, never a negative payable`() {
            assertFailsWith<IllegalArgumentException> { quote(listOf(nila), POST, codeDiscount = 1_450_001) }
        }
    }
}
