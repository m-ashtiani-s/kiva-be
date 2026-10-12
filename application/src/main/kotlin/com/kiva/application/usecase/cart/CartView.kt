package com.kiva.application.usecase.cart

import com.kiva.application.dto.CartDto
import com.kiva.application.dto.CartItemDto
import com.kiva.application.dto.CartReservationDto
import com.kiva.application.repository.OfferQm
import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.policy.DeliveryWindowPolicy
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import java.time.Instant

/**
 * The whole cart as the shopper sees it, for the cart (GetCartUc) and the checkout (GetCheckoutContextUc) alike, so the
 * two pages always show the same cart. Every number comes from the domain: the money and the shipping from
 * OrderPricing (the same call a new order makes), the limits from the cart, the stock from StockPolicy. Only what is
 * seen: never the exact stock.
 *
 * @param offers the offers of every line's color.
 */
internal fun cartDtoOf(cart: Cart, offers: Map<VariantId, OfferQm>, settings: ShopSettings, now: Instant): CartDto {
    val purchaseOffers = offers.mapValues { it.value.offer }
    val quote = OrderPricing.quote(
        lines = cart.lines.map { line -> offerOf(line, offers).offer.let { PricedLine(it.priceTag, Quantity.constructFromPersisted(line.quantity.value), it.isPurchasable) } },
        settings = settings,
        chosenMethod = cart.shippingMethod,
        now = now,
        reserve = cart.reserve,
    )
    val unavailableReason = cart.reservationUnavailableReason(purchaseOffers, settings)

    return CartDto(
        id = cart.publicId.value,
        isGuest = cart.isGuest,
        items = cart.lines.zip(quote.lines).map { (line, totals) ->
            val qm = offerOf(line, offers)
            CartItemDto(
                id = line.itemId.value,
                productId = qm.offer.productId.value,
                variantId = line.variantId.value,
                product = qm.product,
                category = qm.category,
                color = qm.color,
                quantity = line.quantity.value,
                maxQuantity = cart.maxQuantityOf(line, purchaseOffers),
                purchasable = qm.offer.isPurchasable,
                priceTag = qm.offer.priceTag,
                lineTotals = totals,
                stock = StockPolicy.stockOf(qm.offer.available, qm.offer.maxPerOrder),
                addedAt = line.addedOn,
            )
        },
        totals = quote.totals,
        shippingMethod = quote.shipping?.method?.code ?: cart.shippingMethod,
        shippingOptions = quote.shippingOptions,
        freeShipping = quote.freeShipping,
        reservation = CartReservationDto(
            available = unavailableReason == null,
            unavailableReason = unavailableReason,
            enabled = cart.reserve,
            holdDays = settings.reservationHoldDays,
            shipAfterDate = DeliveryWindowPolicy.shipAfterDate(ShopCalendar.dateOf(now), settings.reservationHoldDays),
        ),
        updatedAt = cart.updatedOn,
    )
}

// The read model keeps every color ever made, so a cart line's color is always there; missing is a broken read model.
private fun offerOf(line: CartLine, offers: Map<VariantId, OfferQm>): OfferQm =
    offers[line.variantId] ?: error("No offer for variant ${line.variantId.value} of cart line ${line.itemId.value}")
