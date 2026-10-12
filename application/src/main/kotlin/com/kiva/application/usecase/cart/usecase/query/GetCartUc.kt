package com.kiva.application.usecase.cart.usecase.query

import com.kiva.application.dto.CartDto
import com.kiva.application.dto.CartItemDto
import com.kiva.application.dto.CartReservationDto
import com.kiva.application.repository.OfferQm
import com.kiva.application.repository.OfferQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.policy.DeliveryWindowPolicy
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock
import java.time.Instant

/**
 * The whole cart as the shopper sees it: the body of every cart answer, built after the command that changed or opened
 * it. Every number comes from the domain: the money and the shipping from OrderPricing (the same call the checkout
 * and a new order make), the limits from the cart, the stock from StockPolicy. Only what is seen: never the exact stock.
 *
 * The cart is asked for by the id the command just returned, never by the shopper: there is no owner to check here.
 */
class GetCartUc(
    private val transactionManager: TransactionManager,
    private val cartRepository: CartRepository,
    private val offerQmRepository: OfferQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetCartUc.Qry, CartDto, GetCartUc.Failure> {

    private val logger = getLogger()

    /** [cartId] as the command returned it. */
    data class Qry(val cartId: String)

    override suspend fun execute(query: Qry): Result<CartDto, Failure> {
        logger.info("GetCartUc.execute cartId=${query.cartId}")
        return transactionManager.inTransaction {
            val cart = cartRepository.find(CartPublicId.constructFromPersisted(query.cartId))
                ?: return@inTransaction Result.failure(Failure.CartNotFound)
            val offers = offerQmRepository.findOffers(cart.lines.map { it.variantId })
            Result.success(cartDtoOf(cart, offers, shopSettingsRepository.get(), clock.instant()))
        }
    }

    private fun cartDtoOf(cart: Cart, offers: Map<VariantId, OfferQm>, settings: ShopSettings, now: Instant): CartDto {
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

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Only if the cart was deleted between the command and this query (a merge after a login, at the same moment). */
        data object CartNotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
