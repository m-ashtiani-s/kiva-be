package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.PurchaseQuote
import com.kiva.domain.aggregate.order.model.valueobject.LineTotals
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * What a cart or an order costs (roadmap section 7.1, contract section 3). The cart, the checkout page and placing an
 * order all call [quote], and nothing else adds money up: if two places did the sums, the shopper could see one number
 * and pay another, or get PRICE_CHANGED for no reason.
 *
 * Pure calculations with whole toman: no database, no clock, no Double.
 */
object OrderPricing {

    /**
     * Prices [lines] and their shipping, in one go.
     *
     * The order of the steps is the business rule: lines → `subtotal` → shipping (free shipping looks at the subtotal
     * before the code) → `codeDiscount` → `payable`.
     *
     * @param chosenMethod the method the cart or the order asks for. If it can't be chosen any more, the shop's default
     *   (or the first on offer) is priced instead; see ShippingPolicy.selectedOption.
     * @param now read once by the use case; «today» is its date in Tehran.
     * @param reserve «رزرو ۴ روزه» is on: the order ships after the hold, so delivery is counted from then. Ignored when
     *   the order joins a reservation, which ships with its group.
     * @param codeDiscount round 2: what the discount code takes off, already worked out from the lines it covers, so
     *   never more than the subtotal. Null (no code) until then.
     * @param consolidation the order joins the customer's held reservation; null otherwise.
     */
    fun quote(
        lines: List<PricedLine>,
        settings: ShopSettings,
        chosenMethod: ShippingMethodCode,
        now: Instant,
        reserve: Boolean = false,
        codeDiscount: Money? = null,
        consolidation: ShippingConsolidation? = null,
    ): PurchaseQuote {
        val lineTotals = lines.map(::lineTotalsOf)
        val purchasable = lines.zip(lineTotals).filter { (line, _) -> line.purchasable }.map { (_, totals) -> totals }
        val subtotal = purchasable.map { it.total }.sum()

        val shipDate = DeliveryWindowPolicy.shipDate(
            today = ShopCalendar.dateOf(now),
            holdDays = settings.reservationHoldDays.takeIf { reserve && consolidation == null },
            nonShippingWeekdays = settings.nonShippingWeekdays,
        )
        val options = ShippingPolicy.optionsFor(settings, subtotal, purchasable.isNotEmpty(), shipDate, consolidation)
        val shipping = ShippingPolicy.selectedOption(options, chosenMethod, settings.defaultShippingMethod)
        val shippingCost = shipping?.cost ?: Money.ZERO
        val code = codeDiscount ?: Money.ZERO

        val totals = OrderTotals(
            itemsCount = lines.sumOf { it.quantity.value },
            linesCount = lines.size,
            itemsCompareAtTotal = purchasable.map { it.compareAtTotal ?: it.total }.sum(),
            productDiscount = purchasable.map { it.discount }.sum(),
            subtotal = subtotal,
            codeDiscount = code,
            shippingCost = shippingCost,
            shippingIsFree = shipping?.isFree == true,
            shippingFreeReason = shipping?.freeReason,
            // Never negative: the code takes at most the subtotal (OrderTotals checks it, and Money refuses less than 0).
            payable = subtotal - code + shippingCost,
        )
        return PurchaseQuote(
            lines = lineTotals,
            totals = totals,
            shippingOptions = options,
            shipping = shipping,
            freeShipping = ShippingPolicy.freeShippingProgress(settings, subtotal, consolidation),
        )
    }

    /** The amounts of one line, whether it can be bought or not (the cart shows them either way). */
    fun lineTotalsOf(line: PricedLine): LineTotals {
        val quantity = line.quantity.value
        val total = line.priceTag.price * quantity
        val compareAtTotal = line.priceTag.compareAtPrice?.let { it * quantity }
        return LineTotals(
            total = total,
            compareAtTotal = compareAtTotal,
            discount = compareAtTotal?.let { it - total } ?: Money.ZERO,
        )
    }
}
