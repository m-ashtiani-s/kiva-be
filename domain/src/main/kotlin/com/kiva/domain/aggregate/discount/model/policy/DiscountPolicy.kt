package com.kiva.domain.aggregate.discount.model.policy

import com.kiva.common.railway.unwrapOr
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.DiscountLine
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.order.model.policy.OrderPricing

/**
 * What a discount code takes off (roadmap section 14), with whole toman and integer arithmetic only:
 * - the base is what the lines it covers cost (DiscountCode.covers), among those that can be bought: their `subtotal`;
 * - PERCENT: `floor((base × value + 50_000) / 100_000) × 1_000`, the nearest 1,000 toman, a half up. Then at most
 *   `maxDiscount`, and at most the base. 10% of 3,480,000 is 348,000; of 1,455,000 (145,500) 146,000; of 1,454,990
 *   (145,499) 145,000;
 * - FIXED: its value, at most the base.
 *
 * So it never takes more than the subtotal, and the payable is never negative.
 */
object DiscountPolicy {

    /** What the lines [code] covers cost, among those that can be bought now. */
    fun baseOf(code: DiscountCode, lines: List<DiscountLine>): Money =
        lines.filter { it.offer.isPurchasable && code.covers(it.offer) }.map { OrderPricing.lineTotalsOf(it.priced).total }.sum()

    /** What [code] takes off a [base]. */
    fun amountOf(code: DiscountCode, base: Money): Money = when (code.type) {
        DiscountType.PERCENT -> minOf(percentOf(base, code.value), code.maxDiscount ?: base, base)
        DiscountType.FIXED -> minOf(toman(code.value.toLong()), base)
    }

    /** [percent] of [base], to the nearest 1,000 toman, a half up. */
    fun percentOf(base: Money, percent: Int): Money {
        require(percent in 1..100) { "A percent of $percent" }
        val thousands = Math.addExact(Math.multiplyExact(base.toman, percent.toLong()), HALF_A_THOUSAND_PERCENT) / A_THOUSAND_PERCENT
        return toman(Math.multiplyExact(thousands, 1_000L))
    }

    // Never negative here: a base, a percent and a code's value are all at least 0.
    private fun toman(amount: Long): Money = Money.makeNew(amount).unwrapOr { error("A discount of $amount toman") }

    /** 1,000 toman, in percent of a toman (× 100). */
    private const val A_THOUSAND_PERCENT = 100_000L
    private const val HALF_A_THOUSAND_PERCENT = 50_000L
}
