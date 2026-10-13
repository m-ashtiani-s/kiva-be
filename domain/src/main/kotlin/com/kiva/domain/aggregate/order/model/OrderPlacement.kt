package com.kiva.domain.aggregate.order.model

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * What placing an order works with: what the customer asked for, and what the shop knows right now. The use case
 * gathers it inside its transaction (the cart, the address, the offers read again, the settings); OrderDomainService
 * decides.
 *
 * @property lines the cart's lines, oldest first, each with its color's offer now.
 * @property gatewayAvailable the chosen gateway is switched on and the app has an adapter for it.
 * @property reserve «رزرو ۴ روزه», as the request or else the cart has it.
 * @property expectedPayable what the customer saw on the pay button; null when the client didn't send it (no compare).
 * @property unpaidOrdersHoldingUntil when each of the customer's unpaid orders that still hold stock stops holding it.
 * @property holdingGroup the customer's reservation group still marked HOLDING (round 2), if any: an order to its own
 *   address joins it, and while it holds no new reservation can be asked for.
 */
data class OrderPlacement(
    val userId: UserId,
    val lines: List<Line>,
    val address: AddressSnapshot,
    val messenger: PreShipmentMessenger,
    val shippingMethod: ShippingMethodCode,
    val gatewayAvailable: Boolean,
    val acceptTerms: Boolean,
    val reserve: Boolean,
    val customerNote: CustomerNote?,
    val gift: GiftNote?,
    val expectedPayable: Money?,
    val unpaidOrdersHoldingUntil: List<Instant>,
    val settings: ShopSettings,
    val holdingGroup: ReservationGroup? = null,
) {
    /** A line of the cart: how many, the shop's offer of its color now, and what the catalog shows of it. */
    data class Line(val itemId: CartItemId, val quantity: Quantity, val offer: PurchaseOffer, val item: OrderItemSnapshot)

    override fun toString(): String =
        "OrderPlacement(userId=${userId.value}, lines=${lines.map { "${it.offer.variantId.value}x${it.quantity.value}" }}, " +
            "shippingMethod=$shippingMethod, reserve=$reserve, expectedPayable=${expectedPayable?.toman})"
}
