package com.kiva.domain.aggregate.order

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.policy.ReservationPolicy
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.OrderPlacement
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import com.kiva.domain.aggregate.order.model.policy.PaymentPolicy
import com.kiva.domain.aggregate.order.model.policy.ShippingPolicy
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.order.repository.OrderIdGenerator
import java.time.Duration
import java.time.Instant

/**
 * Placing an order, the way the business wants it done (roadmap step 13), so no use case can get the order of the
 * rules wrong. The contract tells the customer one thing at a time, the most basic first:
 *
 * 0. round 2: an order to the address of the customer's held reservation joins it ([OrderPlacement.holdingGroup]): it
 *    ships with the group's method, free, and its own «رزرو ۴ روزه» is ignored;
 * 1. how it ships: the method must be on offer (SHIPPING_METHOD_UNAVAILABLE), the group's only when it joins one;
 * 2. how it is paid: with something to pay, the gateway must be available (PAYMENT_GATEWAY_UNAVAILABLE, before any order
 *    exists);
 * 3. the terms are accepted (TERMS_NOT_ACCEPTED);
 * 4. «رزرو ۴ روزه», when asked for, is possible (RESERVATION_UNAVAILABLE);
 * 5. the customer doesn't have too many unpaid orders holding stock (429 RATE_LIMITED);
 * 6. every line can be bought: in the shop (PRODUCT_UNAVAILABLE), enough left (OUT_OF_STOCK), within the product's
 *    limit (QUANTITY_EXCEEDS_LIMIT), each naming the cart's lines;
 * 7. the payable is what the customer saw (409 PRICE_CHANGED, with the payable now);
 * 8. then the order gets its id and code, and is placed.
 *
 * What comes before these is the use case's, because the domain can't know it: the idempotency key, an empty cart,
 * the address and the messenger. The money is worked out once, by OrderPricing.quote, the same call the cart and the
 * checkout make.
 *
 * It never saves, never reserves stock and never opens a transaction: the use case does all of that in its one
 * transaction. The other order operations (paid, a failed attempt, expired) are called on [Order] directly (Rule 1).
 */
class OrderDomainService(private val orderIdGenerator: OrderIdGenerator) {

    private val logger = getLogger()

    fun placeOrder(placement: OrderPlacement, now: Instant): Result<Order, PlaceOrderFailure> {
        logger.info("placeOrder $placement")
        val settings = placement.settings
        val lines = placement.lines
        if (lines.isEmpty()) return Result.failure(PlaceOrderFailure.CartEmpty)

        val joins = placement.holdingGroup?.takeIf { it.takes(placement.address, now) }
        val consolidation = joins?.let { ShippingConsolidation(it.shippingMethod) }
        val reserve = placement.reserve && joins == null
        if (placement.shippingMethod !in ShippingPolicy.offeredMethods(settings, consolidation)) {
            return Result.failure(PlaceOrderFailure.ShippingMethodUnavailable)
        }
        val quote = OrderPricing.quote(
            lines = lines.map { PricedLine(it.offer.priceTag, it.quantity, it.offer.isPurchasable) },
            settings = settings,
            chosenMethod = placement.shippingMethod,
            now = now,
            reserve = reserve,
            consolidation = consolidation,
        )
        val payable = quote.totals.payable
        if (payable > Money.ZERO && !placement.gatewayAvailable) return Result.failure(PlaceOrderFailure.GatewayUnavailable)
        if (!placement.acceptTerms) return Result.failure(PlaceOrderFailure.TermsNotAccepted)
        if (reserve) {
            ReservationPolicy.unavailableReason(settings, lines.map { it.offer }, hasActiveReservation = placement.holdingGroup?.isHolding(now) == true)
                ?.let { return Result.failure(PlaceOrderFailure.ReservationUnavailable(it)) }
        }
        PaymentPolicy.refusalToPlace(placement.unpaidOrdersHoldingUntil, now)?.let { return Result.failure(PlaceOrderFailure.TooManyUnpaidOrders(it)) }

        lineProblem(placement)?.let { return Result.failure(it) }
        if (placement.expectedPayable != null && placement.expectedPayable != payable) return Result.failure(PlaceOrderFailure.PriceChanged(payable))

        val id = orderIdGenerator.nextOrderId()
        val orderLines = lines.mapIndexed { index, line ->
            OrderLine(
                lineId = index + 1,
                productId = line.offer.productId,
                variantId = line.offer.variantId,
                item = line.item,
                quantity = line.quantity,
                priceTag = line.offer.priceTag,
                reservable = line.offer.reservable,
                returnable = line.offer.returnable,
            )
        }
        return Order.place(
            id = id,
            code = OrderCodePolicy.codeOf(id),
            userId = placement.userId,
            lines = orderLines,
            limits = lines.associate { it.offer.productId to it.offer.maxPerOrder },
            address = placement.address,
            messenger = placement.messenger,
            shippingMethod = placement.shippingMethod,
            totals = quote.totals,
            reserveRequested = reserve,
            customerNote = placement.customerNote,
            gift = placement.gift,
            paymentPending = Duration.ofMinutes(settings.paymentPendingMinutes.toLong()),
            by = placement.userId,
            now = now,
            reservationGroupId = joins?.id,
        ).mapOnFailure { failure ->
            // Checked just above, so only a bug gets here.
            error("An order checked line by line was refused: ${failure.code}")
        }
    }

    /** The first thing wrong with the lines, naming every cart line it is wrong for; null when they can all be bought. */
    private fun lineProblem(placement: OrderPlacement): PlaceOrderFailure? {
        val lines = placement.lines
        lines.filter { !it.offer.inShop }.takeIf { it.isNotEmpty() }?.let { gone ->
            return PlaceOrderFailure.ProductUnavailable(gone.map { it.itemId })
        }
        lines.filter { it.quantity.value > it.offer.available }.takeIf { it.isNotEmpty() }?.let { short ->
            return PlaceOrderFailure.OutOfStock(short.map { it.itemId })
        }
        val overLimit = Order.productsOverLimit(
            lines.map { it.offer.productId to it.quantity.value },
            lines.associate { it.offer.productId to it.offer.maxPerOrder },
        )
        if (overLimit.isNotEmpty()) return PlaceOrderFailure.QuantityExceedsLimit(lines.filter { it.offer.productId in overLimit }.map { it.itemId })
        return null
    }

    /** Why the order wasn't placed. Nothing was saved, reserved or numbered for any of them. */
    sealed class PlaceOrderFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object CartEmpty : PlaceOrderFailure("CART_EMPTY", FailureKind.BUSINESS_RULE)

        data object ShippingMethodUnavailable : PlaceOrderFailure("SHIPPING_METHOD_UNAVAILABLE", FailureKind.BUSINESS_RULE)

        /** Before the order exists, so without an order code (the contract's first case of this code). */
        data object GatewayUnavailable : PlaceOrderFailure("PAYMENT_GATEWAY_UNAVAILABLE", FailureKind.BUSINESS_RULE)

        data object TermsNotAccepted : PlaceOrderFailure("TERMS_NOT_ACCEPTED", FailureKind.BUSINESS_RULE)

        data class ReservationUnavailable(val reason: ReservationUnavailableReason) :
            PlaceOrderFailure("RESERVATION_UNAVAILABLE", FailureKind.BUSINESS_RULE, mapOf("reason" to reason.name))

        /** 429, with how long until one of the unpaid orders stops holding stock. */
        data class TooManyUnpaidOrders(val refusal: PaymentPolicy.Refusal.TooManyUnpaidOrders) :
            PlaceOrderFailure(refusal.code, refusal.kind, refusal.meta)

        data class ProductUnavailable(val itemIds: List<CartItemId>) :
            PlaceOrderFailure("PRODUCT_UNAVAILABLE", FailureKind.BUSINESS_RULE, mapOf("itemIds" to itemIds.map { it.value }))

        data class OutOfStock(val itemIds: List<CartItemId>) :
            PlaceOrderFailure("OUT_OF_STOCK", FailureKind.BUSINESS_RULE, mapOf("itemIds" to itemIds.map { it.value }))

        /** The product's limit per order went down since these lines were added. */
        data class QuantityExceedsLimit(val itemIds: List<CartItemId>) :
            PlaceOrderFailure("QUANTITY_EXCEEDS_LIMIT", FailureKind.BUSINESS_RULE, mapOf("itemIds" to itemIds.map { it.value }))

        /** 409, with the payable now, so the client can show it and ask again. */
        data class PriceChanged(val payable: Money) : PlaceOrderFailure("PRICE_CHANGED", FailureKind.CONFLICT, mapOf("payable" to payable.toman))
    }
}
