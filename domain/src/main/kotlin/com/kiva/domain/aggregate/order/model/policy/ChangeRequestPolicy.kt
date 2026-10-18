package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import java.math.BigInteger

/**
 * The money of a change request (roadmap section 8.2, rows 4 to 6): how much a swapped line costs more or less, what
 * the customer paid for a line taken out, and how a change is settled against what the order still owes. Pure: the
 * order and the use case give it the numbers.
 */
object ChangeRequestPolicy {

    /**
     * What swapping [oldLine] for a color priced [newPriceTag] (today's price) costs more, or (negative) less:
     * (the new price − the old) × the line's quantity, in toman. Signed, so it is a Long, not Money.
     */
    fun priceDifference(oldLine: OrderLine, newPriceTag: PriceTag): Long =
        Math.multiplyExact(newPriceTag.price.toman - oldLine.priceTag.price.toman, oldLine.quantity.value.toLong())

    /**
     * What the customer paid for [line] of an order placed with [placedTotals]: its total minus its share of the discount
     * code, `floor(total × codeDiscount ÷ subtotal)`. Rounded down, the share taken off is never too small, so what comes
     * back is never more than was paid. Shipping never comes back.
     */
    fun paidShareOf(line: OrderLine, placedTotals: OrderTotals): Money {
        val total = line.totals.total
        return total - codeShareOf(total, placedTotals)
    }

    /**
     * The discount code's share of a line of [lineTotal] in an order placed with [placedTotals] (what [paidShareOf]
     * takes off): `floor(lineTotal × codeDiscount ÷ subtotal)`, 0 without a code.
     */
    fun codeShareOf(lineTotal: Money, placedTotals: OrderTotals): Money {
        val subtotal = placedTotals.subtotal.toman
        if (placedTotals.codeDiscount == Money.ZERO || subtotal == 0L) return Money.ZERO
        // A product of two amounts may not fit a Long; the share itself always does (it is at most the line's total).
        val share = BigInteger.valueOf(lineTotal.toman).multiply(BigInteger.valueOf(placedTotals.codeDiscount.toman)).divide(BigInteger.valueOf(subtotal))
        return Money.constructFromPersisted(share.longValueExact())
    }

    /**
     * A change of [difference] toman (positive: the order costs more; negative: less, or a line's paid share taken out)
     * settled against what the order owes now ([balanceDue]): more is owed; less is first taken off what is owed, and
     * only what is left over goes back as a refund. Owing and refunding at once never happens.
     */
    fun settle(balanceDue: Money, difference: Long): Settlement {
        val owed = Math.addExact(balanceDue.toman, difference)
        return if (owed >= 0) {
            Settlement(balanceDueAfter = Money.constructFromPersisted(owed), refund = Money.ZERO)
        } else {
            Settlement(balanceDueAfter = Money.ZERO, refund = Money.constructFromPersisted(-owed))
        }
    }

    /** How a change ended in money: what the order owes after it, and what goes back to the customer. Never both. */
    data class Settlement(val balanceDueAfter: Money, val refund: Money) {
        init {
            require(balanceDueAfter == Money.ZERO || refund == Money.ZERO) { "A change both owed $balanceDueAfter and refunded $refund" }
        }
    }
}
