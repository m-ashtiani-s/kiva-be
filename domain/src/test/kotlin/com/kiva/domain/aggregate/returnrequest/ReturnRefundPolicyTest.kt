package com.kiva.domain.aggregate.returnrequest

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.OrderFixtures
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.received
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnRefundPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReceipt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * The money a received return suggests (roadmap section 12.2, decision 16), worked out by hand. Three lines: A 2 × 2,000,000,
 * B 1 × 1,000,000, C 3 × 500,000 (subtotal 6,500,000, shipping 145,000). One A and two C come back; B stays.
 */
class ReturnRefundPolicyTest {

    private fun toman(amount: Long) = Money.constructFromPersisted(amount)

    private fun line(lineId: Int, price: Long, quantity: Int) = OrderLine.constructFromPersisted(
        lineId, ProductId.constructFromPersisted(lineId.toLong()), VariantId.constructFromPersisted(100L + lineId), OrderFixtures.item(),
        Quantity.constructFromPersisted(quantity), PriceTag.constructFromPersisted(toman(price), null), reservable = false, returnable = true,
    )

    private val lines = listOf(line(1, 2_000_000, 2), line(2, 1_000_000, 1), line(3, 500_000, 3))

    private fun totals(codeDiscount: Long) = OrderTotals.constructFromPersisted(
        itemsCount = 6, linesCount = 3, itemsCompareAtTotal = toman(6_500_000), productDiscount = Money.ZERO, subtotal = toman(6_500_000),
        codeDiscount = toman(codeDiscount), shippingCost = toman(145_000), shippingIsFree = false, shippingFreeReason = null,
        payable = toman(6_500_000 - codeDiscount + 145_000), discountCode = DiscountCodeText.constructFromPersisted("PAEEZ10"),
    )

    private val oneAAndTwoC = ReturnReceipt.constructFromPersisted(listOf(received(1, 1, 1), received(3, 2, 0)), null)
    private val plenty = toman(100_000_000)

    @Test
    fun `10 percent off - A pays 3,600,000 for two, so one is 1,800,000 - C 1,350,000 for three, so two are 900,000 - 2,700,000`() {
        // A: 4,000,000 − floor(4,000,000 × 650,000 ÷ 6,500,000) = 3,600,000; × 1 ÷ 2 = 1,800,000.
        // C: 1,500,000 − 150,000 = 1,350,000; × 2 ÷ 3 = 900,000.
        assertEquals(toman(2_700_000), ReturnRefundPolicy.suggestedRefund(lines, totals(650_000), oneAAndTwoC, plenty))
    }

    @Test
    fun `15 percent capped at 500,000 - the shares round down, so what comes back never adds up to more than was paid - 2,769,231`() {
        // A: floor(4,000,000 × 500,000 ÷ 6,500,000) = 307,692 off, paid 3,692,308; × 1 ÷ 2 = 1,846,154.
        // C: floor(1,500,000 × 500,000 ÷ 6,500,000) = 115,384 off, paid 1,384,616; × 2 ÷ 3 = 923,077 (923,077.33 rounded down).
        assertEquals(toman(2_769_231), ReturnRefundPolicy.suggestedRefund(lines, totals(500_000), oneAAndTwoC, plenty))
    }

    @Test
    fun `without a code it is the lines' prices, shipping never comes back, and nothing came back is 0`() {
        val noCode = OrderTotals.constructFromPersisted(6, 3, toman(6_500_000), Money.ZERO, toman(6_500_000), Money.ZERO, toman(145_000), false, null, toman(6_645_000))
        val emptyParcel = ReturnReceipt.constructFromPersisted(listOf(received(1, 0, 0)), null)

        assertEquals(toman(3_000_000), ReturnRefundPolicy.suggestedRefund(lines, noCode, oneAAndTwoC, plenty))
        assertEquals(Money.ZERO, ReturnRefundPolicy.suggestedRefund(lines, noCode, emptyParcel, plenty))
    }

    @Test
    fun `never more than the order's money still allows`() {
        assertEquals(toman(2_000_000), ReturnRefundPolicy.suggestedRefund(lines, totals(650_000), oneAAndTwoC, toman(2_000_000)))
    }

    @Test
    fun `a receipt of a line the order doesn't have, or of more than was bought, is a bug and stops loudly`() {
        assertThrows<IllegalStateException> {
            ReturnRefundPolicy.suggestedRefund(lines, totals(0), ReturnReceipt.constructFromPersisted(listOf(received(9, 1, 0)), null), plenty)
        }
        assertThrows<IllegalStateException> {
            ReturnRefundPolicy.suggestedRefund(lines, totals(0), ReturnReceipt.constructFromPersisted(listOf(received(2, 2, 0)), null), plenty)
        }
    }
}
