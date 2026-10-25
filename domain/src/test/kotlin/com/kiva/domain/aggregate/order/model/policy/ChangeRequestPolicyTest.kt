package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.OrderFixtures
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/** The money of a change request (roadmap section 8.2, rows 4 to 6), with the golden numbers of step 3.1. */
class ChangeRequestPolicyTest {

    private fun toman(amount: Long) = Money.constructFromPersisted(amount)

    private fun line(lineId: Int, price: Long, compareAt: Long? = null, quantity: Int = 1) = OrderLine.constructFromPersisted(
        lineId, ProductId.constructFromPersisted(3), VariantId.constructFromPersisted(301), OrderFixtures.item(), Quantity.constructFromPersisted(quantity),
        PriceTag.constructFromPersisted(toman(price), compareAt?.let(::toman)), reservable = true, returnable = true,
    )

    private fun tag(price: Long) = PriceTag.constructFromPersisted(toman(price), null)

    /** Aida caramel at 2,790,000. */
    private val caramel = line(1, 2_790_000, 3_290_000)

    @Nested
    inner class PriceDifference {
        @Test
        fun `Aida caramel 2,790,000 to Aida black at 2,990,000 costs 200,000 more`() {
            assertEquals(200_000L, ChangeRequestPolicy.priceDifference(caramel, tag(2_990_000)))
        }

        @Test
        fun `the same to 2,590,000 costs 200,000 less - negative`() {
            assertEquals(-200_000L, ChangeRequestPolicy.priceDifference(caramel, tag(2_590_000)))
        }

        @Test
        fun `the difference is per bag, times the line's quantity, and a color at the same price costs nothing`() {
            assertEquals(600_000L, ChangeRequestPolicy.priceDifference(line(1, 1_450_000, quantity = 3), tag(1_650_000)))
            assertEquals(0L, ChangeRequestPolicy.priceDifference(caramel, tag(2_790_000)))
        }
    }

    @Nested
    inner class PaidShare {
        private val withCode = OrderTotals.constructFromPersisted(
            2, 2, toman(4_080_000), toman(600_000), toman(3_480_000), toman(348_000), Money.ZERO, true, ShippingFreeReason.THRESHOLD, toman(3_132_000),
            DiscountCodeText.constructFromPersisted("KIVA10"),
        )

        @Test
        fun `an order of 3,480,000 with a code of 348,000 - taking out the 690,000 line gives back 690,000 - floor(690,000 x 348,000 ÷ 3,480,000) = 621,000`() {
            assertEquals(toman(621_000), ChangeRequestPolicy.paidShareOf(line(2, 690_000, 790_000), withCode))
            assertEquals(toman(69_000), ChangeRequestPolicy.codeShareOf(toman(690_000), withCode))
        }

        @Test
        fun `the code's share is rounded down, so what comes back is rounded up and never more than the line cost`() {
            // 1,000 × 1,000 ÷ 3,000 = 333.33…: the share is 333, the line paid 667.
            val totals = OrderTotals.constructFromPersisted(
                3, 3, toman(3_000), Money.ZERO, toman(3_000), toman(1_000), Money.ZERO, true, ShippingFreeReason.THRESHOLD, toman(2_000),
                DiscountCodeText.constructFromPersisted("KIVA10"),
            )

            assertEquals(toman(667), ChangeRequestPolicy.paidShareOf(line(1, 1_000), totals))
        }

        @Test
        fun `without a code the whole line comes back, and shipping never does`() {
            val totals = OrderTotals.constructFromPersisted(
                1, 1, toman(3_290_000), toman(500_000), toman(2_790_000), Money.ZERO, toman(145_000), false, null, toman(2_935_000),
            )

            assertEquals(toman(2_790_000), ChangeRequestPolicy.paidShareOf(caramel, totals))
        }

        @Test
        fun `amounts whose product doesn't fit a Long still give the exact share`() {
            val big = 4_000_000_000_000L
            val totals = OrderTotals.constructFromPersisted(
                2, 2, toman(2 * big), Money.ZERO, toman(2 * big), toman(big), Money.ZERO, true, ShippingFreeReason.THRESHOLD, toman(big),
                DiscountCodeText.constructFromPersisted("KIVA10"),
            )

            assertEquals(toman(big / 2), ChangeRequestPolicy.codeShareOf(toman(big), totals))
        }
    }

    @Nested
    inner class Settle {
        @Test
        fun `costing more is owed`() {
            assertEquals(ChangeRequestPolicy.Settlement(toman(200_000), Money.ZERO), ChangeRequestPolicy.settle(Money.ZERO, 200_000))
        }

        @Test
        fun `costing less with nothing owed is a refund of all of it`() {
            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, toman(200_000)), ChangeRequestPolicy.settle(Money.ZERO, -200_000))
        }

        @Test
        fun `+300,000 then -500,000 - the second is first taken off the 300,000 owed, and only 200,000 is refunded`() {
            val first = ChangeRequestPolicy.settle(Money.ZERO, 300_000)
            val second = ChangeRequestPolicy.settle(first.balanceDueAfter, -500_000)

            assertEquals(ChangeRequestPolicy.Settlement(toman(300_000), Money.ZERO), first)
            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, toman(200_000)), second)
        }

        @Test
        fun `costing less than what is owed only lowers what is owed`() {
            assertEquals(ChangeRequestPolicy.Settlement(toman(100_000), Money.ZERO), ChangeRequestPolicy.settle(toman(300_000), -200_000))
        }

        @Test
        fun `nothing changed keeps what is owed`() {
            assertEquals(ChangeRequestPolicy.Settlement(toman(300_000), Money.ZERO), ChangeRequestPolicy.settle(toman(300_000), 0))
        }

        @Test
        fun `owing and refunding at once is a bug`() {
            assertThrows<IllegalArgumentException> { ChangeRequestPolicy.Settlement(toman(1), toman(1)) }
        }
    }
}
