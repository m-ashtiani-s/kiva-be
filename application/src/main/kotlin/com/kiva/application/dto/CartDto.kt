package com.kiva.application.dto

import com.kiva.application.repository.OfferQm
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.order.model.valueobject.LineTotals
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingQuote
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant
import java.time.LocalDate

/**
 * A cart as the shopper sees it (the contract's Cart, without its Persian texts and without `issues`, which come from
 * the command: see CartChange). Every number is worked out by the domain's policies: the totals and shipping by
 * OrderPricing, the limits by the cart, the stock by StockPolicy. The exact stock is not here.
 *
 * @property items oldest first.
 * @property shippingMethod the method the totals use (the cart's own, unless the shop stopped offering it since).
 * @property freeShipping null when the shop offers no free shipping.
 */
data class CartDto(
    val id: String,
    val isGuest: Boolean,
    val items: List<CartItemDto>,
    val totals: OrderTotals,
    val shippingMethod: ShippingMethodCode,
    val shippingOptions: List<ShippingQuote>,
    val freeShipping: FreeShippingProgress?,
    val reservation: CartReservationDto,
    val consolidation: ConsolidationDto?,
    val updatedAt: Instant,
)

/**
 * The order joins the customer's held «رزرو ۴ روزه» (the contract's ReservationConsolidation): it ships with the group,
 * by the group's method, free. The message is the HTTP layer's.
 *
 * @property reservedOrderCode the order that started the reservation.
 * @property addressId the group's address: only an order to it joins.
 */
data class ConsolidationDto(val reservedOrderCode: String, val expiresAt: Instant, val addressId: Long, val shippingMethod: ShippingMethodCode)

/**
 * One line. [maxQuantity] is what the line may hold now next to the product's other colors; 0 when it can't be bought.
 * [stock] is the color's, through StockPolicy, as on the product page.
 */
data class CartItemDto(
    val id: String,
    val productId: Long,
    val variantId: Long,
    val product: OfferQm.Product,
    val category: OfferQm.Category,
    val color: OfferQm.Color,
    val quantity: Int,
    val maxQuantity: Int,
    val purchasable: Boolean,
    val priceTag: PriceTag,
    val lineTotals: LineTotals,
    val stock: StockInfo,
    val addedAt: Instant,
)

/** The «رزرو ۴ روزه» switch. [shipAfterDate] is today plus [holdDays], in Tehran. */
data class CartReservationDto(
    val available: Boolean,
    val unavailableReason: ReservationUnavailableReason?,
    val enabled: Boolean,
    val holdDays: Int,
    val shipAfterDate: LocalDate,
)
