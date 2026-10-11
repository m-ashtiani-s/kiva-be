package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Money

/**
 * Every total of a cart or an order, from the contract's section 3:
 * `itemsCompareAtTotal` قیمت کالاها − `productDiscount` تخفیف محصولات = `subtotal`;
 * `subtotal` − `codeDiscount` کد تخفیف + `shippingCost` ارسال = `payable` مبلغ قابل پرداخت.
 *
 * Made only by OrderPricing, so the cart, the checkout page and the placed order show the same numbers. The amounts
 * count only the lines that can be bought; [itemsCount] and [linesCount] count every line, because the cart's badge
 * shows what is in the cart.
 *
 * The rules below are the arithmetic itself: totals that don't add up are a bug, and they stop loudly before anybody
 * is asked to pay them.
 *
 * @property itemsCount the quantities of all lines added up (the cart's badge).
 * @property linesCount how many lines.
 * @property subtotal after product discounts: the base of a discount code and of free shipping.
 * @property codeDiscount round 2; 0 until then. Never more than [subtotal], so [payable] is never negative.
 * @property shippingIsFree there is something to ship and shipping costs nothing.
 * @property shippingFreeReason why it is free; null when it isn't.
 */
@ConsistentCopyVisibility
data class OrderTotals internal constructor(
    val itemsCount: Int,
    val linesCount: Int,
    val itemsCompareAtTotal: Money,
    val productDiscount: Money,
    val subtotal: Money,
    val codeDiscount: Money,
    val shippingCost: Money,
    val shippingIsFree: Boolean,
    val shippingFreeReason: ShippingFreeReason?,
    val payable: Money,
) {
    /** «۶۰۰٬۰۰۰ تومان صرفه‌جویی کردی!»: product discounts and the code together. */
    val totalSavings: Money get() = productDiscount + codeDiscount

    init {
        require(linesCount >= 0 && itemsCount >= linesCount) { "Invalid counts: $itemsCount items in $linesCount lines" }
        require(itemsCompareAtTotal == subtotal + productDiscount) { "$itemsCompareAtTotal − $productDiscount is not $subtotal" }
        require(codeDiscount <= subtotal) { "A code discount of $codeDiscount on a subtotal of $subtotal" }
        require(payable == subtotal - codeDiscount + shippingCost) { "$subtotal − $codeDiscount + $shippingCost is not $payable" }
        require(!shippingIsFree || shippingCost == Money.ZERO) { "Free shipping that costs $shippingCost" }
        require(shippingFreeReason == null || shippingIsFree) { "A free reason on shipping that is not free" }
    }
}
