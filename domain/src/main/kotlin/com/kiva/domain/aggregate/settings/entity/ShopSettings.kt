package com.kiva.domain.aggregate.settings.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.DayOfWeek

/**
 * The shop's own numbers: how it ships and what that costs, when shipping is free, which gateways it takes, and how
 * long an order may wait for payment. The contract's AdminSettings is the page that will edit them; until it exists
 * they come from V12__shop_settings.sql and are changed with SQL.
 *
 * Read on every request that needs it, never cached, so a change takes effect at once.
 *
 * @property shippingMethods every method that has a row, active or not, in list order. A method without a row
 *   (COURIER for now) is never offered.
 * @property defaultShippingMethod a new cart's method.
 * @property freeShippingThreshold free shipping starts at this subtotal (after product discounts, before a code)...
 * @property freeShippingMethods ...and only for these methods (POST).
 * @property nonShippingWeekdays days nothing is shipped (Friday); they don't count as working days for delivery.
 * @property paymentPendingMinutes how long a placed order holds its stock while it waits for payment.
 * @property reservationEnabled the «رزرو ۴ روزه» switch is on offer at all.
 * @property reservationHoldDays how long a reservation holds a group of orders, from the first payment.
 * @property returnWindowDays how long after delivery a bag can be sent back.
 * @property paymentGateways every gateway that has a row, active or not, in list order.
 */
class ShopSettings private constructor(
    val shippingMethods: List<ShippingMethod>,
    val defaultShippingMethod: ShippingMethodCode,
    val freeShippingThreshold: Money,
    val freeShippingMethods: Set<ShippingMethodCode>,
    val nonShippingWeekdays: Set<DayOfWeek>,
    val paymentPendingMinutes: Int,
    val reservationEnabled: Boolean,
    val reservationHoldDays: Int,
    val returnWindowDays: Int,
    val paymentGateways: List<PaymentGatewayOption>,
) {
    // The same rules as shopsettings_tbl's CHECKs: a broken row is a bug, stop loudly.
    init {
        require(paymentPendingMinutes in 5..60) { "Invalid paymentPendingMinutes $paymentPendingMinutes" }
        require(reservationHoldDays in 1..14) { "Invalid reservationHoldDays $reservationHoldDays" }
        require(returnWindowDays in 0..60) { "Invalid returnWindowDays $returnWindowDays" }
        require(nonShippingWeekdays.size < DayOfWeek.entries.size) { "At least one day of the week must be left for shipping" }
    }

    override fun toString(): String = "ShopSettings(freeShippingThreshold=$freeShippingThreshold, reservationEnabled=$reservationEnabled)"

    companion object {
        fun constructFromPersisted(
            shippingMethods: List<ShippingMethod>,
            defaultShippingMethod: ShippingMethodCode,
            freeShippingThreshold: Money,
            freeShippingMethods: Set<ShippingMethodCode>,
            nonShippingWeekdays: Set<DayOfWeek>,
            paymentPendingMinutes: Int,
            reservationEnabled: Boolean,
            reservationHoldDays: Int,
            returnWindowDays: Int,
            paymentGateways: List<PaymentGatewayOption>,
        ): ShopSettings = ShopSettings(
            shippingMethods, defaultShippingMethod, freeShippingThreshold, freeShippingMethods, nonShippingWeekdays,
            paymentPendingMinutes, reservationEnabled, reservationHoldDays, returnWindowDays, paymentGateways,
        )
    }
}
