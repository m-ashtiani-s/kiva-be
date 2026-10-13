package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.order.model.valueobject.ShippingQuote
import com.kiva.domain.aggregate.order.model.valueobject.ShippingUnavailableReason
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.LocalDate

/**
 * How an order may ship and what that costs (roadmap section 7.2). Pure calculations: the cart, the checkout page and
 * the placed order all get their options from here, so the three never disagree.
 *
 * Every amount is about the subtotal **after product discounts and before a discount code**: a code never takes free
 * shipping away.
 */
object ShippingPolicy {

    /**
     * One option per active method, in the shop's list order. A method that is switched off, or has no row (COURIER),
     * is not offered at all.
     *
     * @param hasPurchasableLines false for a cart whose lines are all out of stock or gone: nothing ships, so every
     *   option costs 0 and none is «free».
     * @param shipDate the day the order would leave the shop (DeliveryWindowPolicy.shipDate); delivery is counted from it.
     * @param consolidation the order joins the customer's held reservation; null otherwise.
     */
    fun optionsFor(
        settings: ShopSettings,
        subtotal: Money,
        hasPurchasableLines: Boolean,
        shipDate: LocalDate,
        consolidation: ShippingConsolidation?,
    ): List<ShippingQuote> =
        settings.shippingMethods
            .filter { it.isActive }
            .map { quoteOf(it, settings, subtotal, hasPurchasableLines, shipDate, consolidation) }

    /**
     * The option the order actually ships by: the one [methodFor] picks among those that can be chosen. Null only when
     * the shop has switched every method off; such an order can't be placed.
     */
    fun selectedOption(options: List<ShippingQuote>, chosen: ShippingMethodCode, default: ShippingMethodCode): ShippingQuote? {
        val method = methodFor(options.filter { it.available }.map { it.method.code }, chosen, default)
        return options.firstOrNull { it.method.code == method }
    }

    /**
     * The methods an order can be shipped by, in the shop's order: the active ones, and while it joins a reservation
     * (round 2) only the group's. The same set [optionsFor] marks as available.
     */
    fun offeredMethods(settings: ShopSettings, consolidation: ShippingConsolidation?): List<ShippingMethodCode> =
        settings.shippingMethods
            .filter { it.isActive && (consolidation == null || it.code == consolidation.method) }
            .map { it.code }

    /**
     * The one rule for which method is used: [chosen] while it is [offered], otherwise the shop's [default], otherwise
     * the first offered; null when nothing is. The pricing ships by it, and the cart's sync moves a cart to it with
     * SHIPPING_METHOD_CHANGED.
     */
    fun methodFor(offered: List<ShippingMethodCode>, chosen: ShippingMethodCode, default: ShippingMethodCode): ShippingMethodCode? =
        chosen.takeIf { it in offered } ?: default.takeIf { it in offered } ?: offered.firstOrNull()

    /**
     * The «تا ارسال رایگان» bar. Its method is the first active method with free shipping (POST); null when the shop
     * has no active one, because there is nothing to promise. An order that joins a reservation (round 2) already ships
     * free by the group's method, so its bar is full.
     *
     * `progressPercent` from the contract is `min(100, subtotal × 100 / threshold)` with one decimal, halves rounded up.
     * It is worked out in tenths of a percent with whole numbers, the same way as PricePolicy.discountPercent:
     * 1,450,000 of 3,000,000 is 483.33‰, so 483, shown as 48.3.
     */
    fun freeShippingProgress(settings: ShopSettings, subtotal: Money, consolidation: ShippingConsolidation?): FreeShippingProgress? {
        val threshold = settings.freeShippingThreshold
        if (consolidation != null) {
            return FreeShippingProgress(threshold, consolidation.method, eligible = true, remaining = Money.ZERO, FreeShippingProgress.FULL)
        }
        val method = settings.shippingMethods.firstOrNull { it.isActive && it.code in settings.freeShippingMethods } ?: return null
        if (subtotal >= threshold) {
            return FreeShippingProgress(threshold, method.code, eligible = true, remaining = Money.ZERO, FreeShippingProgress.FULL)
        }
        // threshold > subtotal >= 0 here, so there is no division by zero.
        // round(subtotal / threshold × 1000) = floor((subtotal × 2000 + threshold) / (2 × threshold))
        val perMille = (Math.addExact(Math.multiplyExact(subtotal.toman, 2000), threshold.toman) / Math.multiplyExact(threshold.toman, 2)).toInt()
        return FreeShippingProgress(
            threshold = threshold,
            method = method.code,
            eligible = false,
            remaining = threshold - subtotal,
            // 2,999,999 of 3,000,000 rounds to 1000, but a toman is still missing: the bar stays just short of full.
            progressPerMille = minOf(perMille, FreeShippingProgress.FULL - 1),
        )
    }

    private fun quoteOf(
        method: ShippingMethod,
        settings: ShopSettings,
        subtotal: Money,
        hasPurchasableLines: Boolean,
        shipDate: LocalDate,
        consolidation: ShippingConsolidation?,
    ): ShippingQuote {
        val isGroupMethod = consolidation?.method == method.code
        val freeReason = when {
            !hasPurchasableLines -> null
            consolidation != null -> ShippingFreeReason.RESERVATION_CONSOLIDATION.takeIf { isGroupMethod }
            method.code in settings.freeShippingMethods && subtotal >= settings.freeShippingThreshold -> ShippingFreeReason.THRESHOLD
            else -> null
        }
        val cost = if (hasPurchasableLines && freeReason == null) method.baseCost else Money.ZERO
        return ShippingQuote(
            method = method,
            cost = cost,
            isFree = hasPurchasableLines && cost == Money.ZERO,
            freeReason = freeReason,
            delivery = DeliveryWindowPolicy.windowOf(shipDate, method.deliveryDays, settings.nonShippingWeekdays),
            unavailableReason = ShippingUnavailableReason.CONSOLIDATED_WITH_RESERVATION.takeIf { consolidation != null && !isGroupMethod },
        )
    }
}
