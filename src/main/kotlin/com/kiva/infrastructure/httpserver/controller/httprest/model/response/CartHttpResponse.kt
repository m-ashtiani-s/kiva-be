package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AppliedDiscountDto
import com.kiva.application.dto.CartDto
import com.kiva.application.dto.CartIssueDto
import com.kiva.application.dto.CartItemDto
import com.kiva.application.dto.CartReservationDto
import com.kiva.application.dto.ConsolidationDto
import com.kiva.application.dto.RemovedItemDto
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingQuote
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal

/** Contract schema `Cart`, built from GetCartUc's CartDto and the command's issues. */
@Serializable
data class CartHttpResponse(
    val id: String,
    val isGuest: Boolean,
    val items: List<CartItemHttpResponse>,
    val totals: CartTotalsHttpResponse,
    val shippingMethod: String,
    val shippingOptions: List<ShippingOptionQuoteHttpResponse>,
    val freeShipping: FreeShippingProgressHttpResponse?,
    val reservation: CartReservationHttpResponse,
    val consolidation: ReservationConsolidationHttpResponse?,
    val discount: AppliedDiscountHttpResponse?,
    val issues: List<CartIssueHttpResponse>,
    val checkoutRequiresLogin: Boolean,
    val updatedAt: String,
) {
    companion object {
        fun from(cart: CartDto, issues: List<CartIssueDto>, urls: MediaUrls): CartHttpResponse {
            val methodNames = cart.shippingOptions.associate { it.method.code to it.method.name }
            fun nameOf(method: ShippingMethodCode): String = methodNames[method] ?: method.name
            val chosenName = nameOf(cart.shippingMethod)

            return CartHttpResponse(
                id = cart.id,
                isGuest = cart.isGuest,
                items = cart.items.map { CartItemHttpResponse.from(it, urls) },
                totals = CartTotalsHttpResponse.from(cart.totals),
                shippingMethod = cart.shippingMethod.name,
                shippingOptions = cart.shippingOptions.map { ShippingOptionQuoteHttpResponse.from(it, cart.shippingMethod, ::nameOf) },
                freeShipping = cart.freeShipping?.let { FreeShippingProgressHttpResponse.from(it, nameOf(it.method)) },
                reservation = CartReservationHttpResponse.from(cart.reservation),
                consolidation = cart.consolidation?.let(ReservationConsolidationHttpResponse::from),
                discount = cart.discount?.let(AppliedDiscountHttpResponse::from),
                issues = issues.map { CartIssueHttpResponse(it.code.name, it.itemId, CartTexts.issue(it, chosenName), it.previousValue, it.currentValue) },
                // A guest is asked to log in with their mobile number before paying.
                checkoutRequiresLogin = cart.isGuest,
                updatedAt = cart.updatedAt.toString(),
            )
        }
    }
}

/**
 * Contract schema `CartItem`. [image] is the color's first photo, `null` while it has none (as on the product card);
 * [isWishlisted]: true or false on a customer's cart, null on a guest's (phase 9, decision 15).
 */
@Serializable
data class CartItemHttpResponse(
    val id: String,
    val productId: Long,
    val variantId: Long,
    val product: CartItemProductHttpResponse,
    val color: ColorHttpResponse,
    val image: MediaAssetHttpResponse?,
    val quantity: Int,
    val maxQuantity: Int,
    val purchasable: Boolean,
    val unitPrice: Long,
    val unitCompareAtPrice: Long?,
    val lineTotal: Long,
    val lineCompareAtTotal: Long?,
    val lineDiscount: Long,
    val stock: StockInfoHttpResponse,
    val isWishlisted: Boolean?,
    val addedAt: String,
) {
    companion object {
        fun from(item: CartItemDto, urls: MediaUrls) = CartItemHttpResponse(
            id = item.id,
            productId = item.productId,
            variantId = item.variantId,
            product = CartItemProductHttpResponse(
                id = item.productId,
                slug = item.product.slug,
                sku = item.product.sku,
                name = item.product.name,
                category = CategoryRefHttpResponse(item.category.id, item.category.slug, item.category.name),
                url = "/product/${item.product.slug}?color=${item.color.key}",
            ),
            color = item.color.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) },
            image = item.image?.let { MediaAssetHttpResponse.from(it, urls) },
            quantity = item.quantity,
            maxQuantity = item.maxQuantity,
            purchasable = item.purchasable,
            unitPrice = item.priceTag.price.toman,
            unitCompareAtPrice = item.priceTag.compareAtPrice?.toman,
            lineTotal = item.lineTotals.total.toman,
            lineCompareAtTotal = item.lineTotals.compareAtTotal?.toman,
            lineDiscount = item.lineTotals.discount.toman,
            stock = StockInfoHttpResponse.from(item.stock),
            isWishlisted = item.isWishlisted,
            addedAt = item.addedAt.toString(),
        )
    }
}

/** The `product` of a CartItem: enough to name it and link to its page in the line's color. */
@Serializable
data class CartItemProductHttpResponse(
    val id: Long,
    val slug: String,
    val sku: String,
    val name: String,
    val category: CategoryRefHttpResponse,
    val url: String,
)

/** Contract schema `CartTotals`, in toman. */
@Serializable
data class CartTotalsHttpResponse(
    val itemsCount: Int,
    val linesCount: Int,
    val itemsCompareAtTotal: Long,
    val productDiscount: Long,
    val subtotal: Long,
    val codeDiscount: Long,
    val shippingCost: Long,
    val shippingIsFree: Boolean,
    val payable: Long,
    val totalSavings: Long,
    val currency: String,
) {
    companion object {
        fun from(totals: OrderTotals) = CartTotalsHttpResponse(
            itemsCount = totals.itemsCount,
            linesCount = totals.linesCount,
            itemsCompareAtTotal = totals.itemsCompareAtTotal.toman,
            productDiscount = totals.productDiscount.toman,
            subtotal = totals.subtotal.toman,
            codeDiscount = totals.codeDiscount.toman,
            shippingCost = totals.shippingCost.toman,
            shippingIsFree = totals.shippingIsFree,
            payable = totals.payable.toman,
            totalSavings = totals.totalSavings.toman,
            currency = PriceInfoHttpResponse.TOMAN,
        )
    }
}

/** Contract schema `ShippingOptionQuote`. */
@Serializable
data class ShippingOptionQuoteHttpResponse(
    val method: String,
    val name: String,
    val description: String,
    val icon: String,
    val baseCost: Long,
    val cost: Long,
    val isFree: Boolean,
    val freeReason: String?,
    val estimatedDelivery: EstimatedDeliveryHttpResponse,
    val available: Boolean,
    val unavailableReason: String?,
    val selected: Boolean,
) {
    companion object {
        /** @param nameOf the name of a method on offer, for the reason an option can't be chosen. */
        fun from(quote: ShippingQuote, chosen: ShippingMethodCode, nameOf: (ShippingMethodCode) -> String): ShippingOptionQuoteHttpResponse {
            val method = quote.method
            return ShippingOptionQuoteHttpResponse(
                method = method.code.name,
                name = method.name,
                description = CartTexts.delivery(method.deliveryDays),
                icon = method.icon,
                baseCost = method.baseCost.toman,
                cost = quote.cost.toman,
                isFree = quote.isFree,
                freeReason = quote.freeReason?.name,
                estimatedDelivery = EstimatedDeliveryHttpResponse(
                    minDays = method.deliveryDays.min,
                    maxDays = method.deliveryDays.max,
                    fromDate = quote.delivery.fromDate.toString(),
                    toDate = quote.delivery.toDate.toString(),
                ),
                available = quote.available,
                unavailableReason = quote.unavailableReason?.let { CartTexts.shippingUnavailable(it, nameOf(chosen)) },
                selected = method.code == chosen,
            )
        }
    }
}

/** When it would arrive, as dates in Tehran (`YYYY-MM-DD`). */
@Serializable
data class EstimatedDeliveryHttpResponse(val minDays: Int, val maxDays: Int, val fromDate: String, val toDate: String)

/** Contract schema `FreeShippingProgress`. [progressPercent] has one decimal (48.3), written without a Double. */
@Serializable
data class FreeShippingProgressHttpResponse(
    val threshold: Long,
    val method: String,
    val eligible: Boolean,
    val remaining: Long,
    @Serializable(with = PerMilleAsPercentSerializer::class)
    val progressPercent: Int,
    val message: String,
) {
    companion object {
        fun from(progress: FreeShippingProgress, methodName: String) = FreeShippingProgressHttpResponse(
            threshold = progress.threshold.toman,
            method = progress.method.name,
            eligible = progress.eligible,
            remaining = progress.remaining.toman,
            progressPercent = progress.progressPerMille,
            message = CartTexts.freeShipping(progress, methodName),
        )
    }
}

/**
 * Contract schema `CartReservation`. [unavailableReason] is the code itself (the contract's enum). [timeline] is the
 * switch's three cards, with Jalali dates; empty while the shop has reservations switched off (contract).
 */
@Serializable
data class CartReservationHttpResponse(
    val available: Boolean,
    val unavailableReason: String?,
    val enabled: Boolean,
    val holdDays: Int,
    val shipAfterDate: String,
    val timeline: List<ReservationTimelineCardHttpResponse>,
) {
    companion object {
        fun from(reservation: CartReservationDto) = CartReservationHttpResponse(
            available = reservation.available,
            unavailableReason = reservation.unavailableReason?.name,
            enabled = reservation.enabled,
            holdDays = reservation.holdDays,
            shipAfterDate = reservation.shipAfterDate.toString(),
            timeline = if (reservation.unavailableReason == ReservationUnavailableReason.RESERVATION_DISABLED) {
                emptyList()
            } else {
                ReservationTexts.timeline(reservation.shipAfterDate.minusDays(reservation.holdDays.toLong()), reservation.shipAfterDate, reservation.holdDays)
            },
        )
    }
}

/** One card of the reservation's timeline. */
@Serializable
data class ReservationTimelineCardHttpResponse(val title: String, val text: String)

/** Contract schema `ReservationConsolidation`: the order joins the customer's held reservation, shipping free. */
@Serializable
data class ReservationConsolidationHttpResponse(
    val reservedOrderCode: String,
    val expiresAt: String,
    val addressId: Long,
    val shippingWaived: Boolean,
    val shippingMethod: String,
    val message: String,
) {
    companion object {
        fun from(consolidation: ConsolidationDto) = ReservationConsolidationHttpResponse(
            reservedOrderCode = consolidation.reservedOrderCode,
            expiresAt = consolidation.expiresAt.toString(),
            addressId = consolidation.addressId,
            shippingWaived = true,
            shippingMethod = consolidation.shippingMethod.name,
            message = ReservationTexts.joins(consolidation.reservedOrderCode),
        )
    }
}

/** Contract schema `AppliedDiscount`: the cart's code, and what it takes off now. */
@Serializable
data class AppliedDiscountHttpResponse(val code: String, val label: String, val type: String, val value: Int, val amount: Long) {
    companion object {
        fun from(discount: AppliedDiscountDto) = AppliedDiscountHttpResponse(discount.code, discount.label, discount.type.name, discount.value, discount.amount.toman)
    }
}

/** Contract schema `CartIssue`. */
@Serializable
data class CartIssueHttpResponse(
    val code: String,
    val itemId: String?,
    val message: String,
    val previousValue: Long?,
    val currentValue: Long?,
)

/** Contract schema `CartMutationResponse`: the cart after adding, changing or removing a line. */
@Serializable
data class CartMutationHttpResponse(
    val cart: CartHttpResponse,
    val affectedItemId: String?,
    val removedItem: RemovedItemHttpResponse?,
    val message: String?,
)

/** What «برگردون» posts again: the color and the quantity. [name] is the product's. */
@Serializable
data class RemovedItemHttpResponse(val variantId: Long, val quantity: Int, val name: String) {
    companion object {
        fun from(removed: RemovedItemDto) = RemovedItemHttpResponse(removed.variantId, removed.quantity, removed.productName)
    }
}

/**
 * Writes tenths of a percent (483) as the contract's number with one decimal (48.3), digit for digit: no Double
 * between the domain's whole number and the JSON text. Responses only.
 */
object PerMilleAsPercentSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PerMilleAsPercent", PrimitiveKind.DOUBLE)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Int) {
        val json = encoder as? JsonEncoder ?: error("PerMilleAsPercent is written as JSON only")
        json.encodeJsonElement(JsonUnquotedLiteral(BigDecimal.valueOf(value.toLong(), 1).toPlainString()))
    }

    override fun deserialize(decoder: Decoder): Int = BigDecimal(decoder.decodeString()).movePointRight(1).intValueExact()
}
