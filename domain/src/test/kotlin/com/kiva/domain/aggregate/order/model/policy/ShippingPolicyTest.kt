package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.PricingFixtures.date
import com.kiva.domain.aggregate.order.PricingFixtures.post
import com.kiva.domain.aggregate.order.PricingFixtures.shopSettings
import com.kiva.domain.aggregate.order.PricingFixtures.tipax
import com.kiva.domain.aggregate.order.PricingFixtures.toman
import com.kiva.domain.aggregate.order.model.valueobject.DeliveryWindow
import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.order.model.valueobject.ShippingQuote
import com.kiva.domain.aggregate.order.model.valueobject.ShippingUnavailableReason
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.COURIER
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.TIPAX
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShippingPolicyTest {

    private val thursday = date("2026-10-15")

    private fun options(
        subtotal: Long,
        settings: ShopSettings = shopSettings(),
        hasPurchasableLines: Boolean = true,
        consolidation: ShippingConsolidation? = null,
    ): Map<ShippingMethodCode, ShippingQuote> =
        ShippingPolicy.optionsFor(settings, toman(subtotal), hasPurchasableLines, thursday, consolidation).associateBy { it.method.code }

    @Nested
    inner class Options {
        @Test
        fun `every active method is offered, in the shop's order, each with its own delivery dates`() {
            val options = ShippingPolicy.optionsFor(shopSettings(), toman(1_450_000), true, thursday, null)

            assertEquals(listOf(TIPAX, POST), options.map { it.method.code })
            assertEquals(DeliveryWindow(date("2026-10-17"), date("2026-10-18")), options[0].delivery)
            assertEquals(DeliveryWindow(date("2026-10-19"), date("2026-10-21")), options[1].delivery)
        }

        @Test
        fun `a switched-off method is not offered at all`() {
            val options = ShippingPolicy.optionsFor(shopSettings(listOf(tipax(isActive = false), post())), toman(1_450_000), true, thursday, null)

            assertEquals(listOf(POST), options.map { it.method.code })
        }

        @Test
        fun `below the threshold every method costs its base cost and can be chosen`() {
            val options = options(subtotal = 1_450_000)

            assertEquals(toman(145_000), options.getValue(TIPAX).cost)
            assertEquals(toman(75_000), options.getValue(POST).cost)
            assertTrue(options.values.all { !it.isFree && it.freeReason == null && it.available && it.unavailableReason == null })
        }

        @Test
        fun `a subtotal of exactly 3,000,000 ships free by post`() {
            val post = options(subtotal = 3_000_000).getValue(POST)

            assertEquals(Money.ZERO, post.cost)
            assertTrue(post.isFree)
            assertEquals(ShippingFreeReason.THRESHOLD, post.freeReason)
            assertEquals(toman(75_000), post.method.baseCost) // still sent, to be shown crossed out
        }

        @Test
        fun `2,999,000 does not`() {
            val post = options(subtotal = 2_999_000).getValue(POST)

            assertEquals(toman(75_000), post.cost)
            assertFalse(post.isFree)
        }

        @Test
        fun `tipax is never free, however big the subtotal`() {
            val tipax = options(subtotal = 50_000_000).getValue(TIPAX)

            assertEquals(toman(145_000), tipax.cost)
            assertFalse(tipax.isFree)
            assertNull(tipax.freeReason)
        }

        @Test
        fun `with nothing to ship, every option costs 0 but none is «free»`() {
            val options = options(subtotal = 0, hasPurchasableLines = false)

            assertTrue(options.values.all { it.cost == Money.ZERO && !it.isFree && it.freeReason == null && it.available })
        }

        @Test
        fun `a method whose own price is 0 ships free without a reason`() {
            val settings = shopSettings(listOf(tipax(baseCost = 0), post()))

            val tipax = options(subtotal = 1_450_000, settings = settings).getValue(TIPAX)

            assertTrue(tipax.isFree)
            assertNull(tipax.freeReason)
        }
    }

    @Nested
    inner class Consolidation {
        private val joinsPostGroup = ShippingConsolidation(POST)

        @Test
        fun `the group's method costs nothing, even below the threshold`() {
            val post = options(subtotal = 1_450_000, consolidation = joinsPostGroup).getValue(POST)

            assertEquals(Money.ZERO, post.cost)
            assertTrue(post.isFree)
            assertEquals(ShippingFreeReason.RESERVATION_CONSOLIDATION, post.freeReason)
            assertTrue(post.available)
        }

        @Test
        fun `another method keeps its price and can't be chosen`() {
            val tipax = options(subtotal = 1_450_000, consolidation = joinsPostGroup).getValue(TIPAX)

            assertEquals(toman(145_000), tipax.cost)
            assertFalse(tipax.isFree)
            assertFalse(tipax.available)
            assertEquals(ShippingUnavailableReason.CONSOLIDATED_WITH_RESERVATION, tipax.unavailableReason)
        }
    }

    @Nested
    inner class Selected {
        @Test
        fun `the chosen method, while it can be chosen`() {
            val options = ShippingPolicy.optionsFor(shopSettings(), toman(1_450_000), true, thursday, null)

            assertEquals(TIPAX, ShippingPolicy.selectedOption(options, chosen = TIPAX, default = POST)?.method?.code)
        }

        @Test
        fun `a chosen method that is no longer offered falls back to the shop's default`() {
            val options = ShippingPolicy.optionsFor(shopSettings(listOf(tipax(isActive = false), post())), toman(1_450_000), true, thursday, null)

            assertEquals(POST, ShippingPolicy.selectedOption(options, chosen = TIPAX, default = POST)?.method?.code)
            assertEquals(POST, ShippingPolicy.selectedOption(options, chosen = COURIER, default = POST)?.method?.code)
        }

        @Test
        fun `and when the default is gone too, the first method on offer`() {
            val options = ShippingPolicy.optionsFor(shopSettings(listOf(tipax(), post(isActive = false))), toman(1_450_000), true, thursday, null)

            assertEquals(TIPAX, ShippingPolicy.selectedOption(options, chosen = COURIER, default = POST)?.method?.code)
        }

        @Test
        fun `nothing on offer selects nothing`() {
            val options = ShippingPolicy.optionsFor(shopSettings(listOf(tipax(isActive = false), post(isActive = false))), toman(1_450_000), true, thursday, null)

            assertNull(ShippingPolicy.selectedOption(options, chosen = POST, default = POST))
        }

        @Test
        fun `offered are the active methods in the shop's order, and while joining a reservation only the group's`() {
            assertEquals(listOf(TIPAX, POST), ShippingPolicy.offeredMethods(shopSettings(), consolidation = null))
            assertEquals(listOf(POST), ShippingPolicy.offeredMethods(shopSettings(listOf(tipax(isActive = false), post())), consolidation = null))
            assertEquals(listOf(TIPAX), ShippingPolicy.offeredMethods(shopSettings(), ShippingConsolidation(TIPAX)))
        }

        @Test
        fun `the one rule - chosen while offered, else the default, else the first offered, else nothing`() {
            assertEquals(TIPAX, ShippingPolicy.methodFor(listOf(TIPAX, POST), chosen = TIPAX, default = POST))
            assertEquals(POST, ShippingPolicy.methodFor(listOf(TIPAX, POST), chosen = COURIER, default = POST))
            assertEquals(TIPAX, ShippingPolicy.methodFor(listOf(TIPAX), chosen = COURIER, default = POST))
            assertNull(ShippingPolicy.methodFor(emptyList(), chosen = POST, default = POST))
        }

        @Test
        fun `joining a reservation selects the group's method, whatever was chosen`() {
            val options = ShippingPolicy.optionsFor(shopSettings(), toman(1_450_000), true, thursday, ShippingConsolidation(POST))

            assertEquals(POST, ShippingPolicy.selectedOption(options, chosen = TIPAX, default = TIPAX)?.method?.code)
        }
    }

    @Nested
    inner class FreeShippingBar {
        private fun progress(subtotal: Long, settings: ShopSettings = shopSettings()): FreeShippingProgress? =
            ShippingPolicy.freeShippingProgress(settings, toman(subtotal), consolidation = null)

        @Test
        fun `the roadmap's example - 1,450,000 is 1,550,000 short, 48 point 3 percent`() {
            assertEquals(
                FreeShippingProgress(toman(3_000_000), POST, eligible = false, remaining = toman(1_550_000), progressPerMille = 483),
                progress(1_450_000),
            )
        }

        @Test
        fun `halves round up - 1,501,500 is exactly 50 point 05 percent and shows 50 point 1`() {
            assertEquals(501, progress(1_501_500)?.progressPerMille)
            assertEquals(500, progress(1_501_499)?.progressPerMille)
        }

        @Test
        fun `at the threshold the bar is full and nothing is left`() {
            assertEquals(
                FreeShippingProgress(toman(3_000_000), POST, eligible = true, remaining = Money.ZERO, progressPerMille = 1000),
                progress(3_000_000),
            )
            assertEquals(1000, progress(9_000_000)?.progressPerMille)
        }

        @Test
        fun `one toman short is not a full bar, though it rounds to 100 percent`() {
            val progress = progress(2_999_999)

            assertEquals(999, progress?.progressPerMille)
            assertEquals(toman(1), progress?.remaining)
            assertEquals(false, progress?.eligible)
        }

        @Test
        fun `an empty cart starts at 0`() {
            assertEquals(
                FreeShippingProgress(toman(3_000_000), POST, eligible = false, remaining = toman(3_000_000), progressPerMille = 0),
                progress(0),
            )
        }

        @Test
        fun `a threshold of 0 means everything ships free, without dividing by zero`() {
            assertEquals(1000, progress(0, shopSettings(freeShippingThreshold = 0))?.progressPerMille)
        }

        @Test
        fun `its method is the first active free method in the shop's order`() {
            val bothFree = shopSettings(freeShippingMethods = setOf(TIPAX, POST))
            val postOff = shopSettings(listOf(tipax(), post(isActive = false)), freeShippingMethods = setOf(TIPAX, POST))

            assertEquals(TIPAX, progress(1_450_000, bothFree)?.method)
            assertEquals(TIPAX, progress(1_450_000, postOff)?.method)
        }

        @Test
        fun `no bar when the shop has no free shipping on offer`() {
            assertNull(progress(1_450_000, shopSettings(freeShippingMethods = emptySet())))
            assertNull(progress(1_450_000, shopSettings(listOf(tipax(), post(isActive = false)))))
        }

        @Test
        fun `joining a reservation fills the bar with the group's method`() {
            assertEquals(
                FreeShippingProgress(toman(3_000_000), TIPAX, eligible = true, remaining = Money.ZERO, progressPerMille = 1000),
                ShippingPolicy.freeShippingProgress(shopSettings(), toman(1_450_000), ShippingConsolidation(TIPAX)),
            )
        }
    }
}
