package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.entity.OrderLine

/**
 * The totals an order shows (the contract's OrderTotals) as it stands now, after any change request (roadmap section 8.2,
 * rows 7 and 12). The totals it was placed with ([OrderTotals]) are a financial record and never change; these are made
 * from them, its lines now, and its money since:
 *
 * - [itemsCompareAtTotal], [productDiscount], [subtotal] and the counts are the current lines' (a swapped line at the
 *   price of its swap);
 * - [shippingCost] is the placed one; [codeDiscount] too, minus the code's share of the lines taken out;
 * - [payable] is everything paid so far (the placed payable and the balances paid), [balanceDue] what is still owed, and
 *   [refunded] the completed refunds.
 *
 * Before any change they are the placed totals, with [balanceDue] 0. Always (the test of row 7):
 * `subtotal − codeDiscount + shippingCost = payable + balanceDue − the refunds the changes made`.
 */
data class OrderTotalsView(
    val itemsCount: Int,
    val linesCount: Int,
    val itemsCompareAtTotal: Money,
    val productDiscount: Money,
    val subtotal: Money,
    val discountCode: DiscountCodeText?,
    val codeDiscount: Money,
    val shippingCost: Money,
    val shippingIsFree: Boolean,
    val shippingFreeReason: ShippingFreeReason?,
    val payable: Money,
    val balanceDue: Money,
    val refunded: Money,
) {
    init {
        require(itemsCompareAtTotal == subtotal + productDiscount) { "$itemsCompareAtTotal − $productDiscount is not $subtotal" }
    }

    companion object {
        /**
         * The totals of an order [placed] with those totals, with [lines] now, [codeDiscountRemoved] (the code's share of
         * the lines taken out), [balancePaid] and [balanceDue] after its changes, and [refunded] by its completed refunds.
         */
        fun of(
            placed: OrderTotals,
            lines: List<OrderLine>,
            codeDiscountRemoved: Money,
            balancePaid: Money,
            balanceDue: Money,
            refunded: Money,
        ): OrderTotalsView {
            val lineTotals = lines.map { it.totals }
            val subtotal = lineTotals.map { it.total }.sum()
            val productDiscount = lineTotals.map { it.discount }.sum()
            return OrderTotalsView(
                itemsCount = lines.sumOf { it.quantity.value },
                linesCount = lines.size,
                itemsCompareAtTotal = subtotal + productDiscount,
                productDiscount = productDiscount,
                subtotal = subtotal,
                discountCode = placed.discountCode,
                codeDiscount = placed.codeDiscount - codeDiscountRemoved,
                shippingCost = placed.shippingCost,
                shippingIsFree = placed.shippingIsFree,
                shippingFreeReason = placed.shippingFreeReason,
                payable = placed.payable + balancePaid,
                balanceDue = balanceDue,
                refunded = refunded,
            )
        }
    }
}
