package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CartIssueDto
import com.kiva.application.dto.CheckoutContextDto
import com.kiva.application.port.PaymentGateways
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `CheckoutContext`, built from GetCheckoutContextUc's answer and the issues OpenCartUc found.
 * [shippingOptions], [reservation] and [consolidation] are the [cart]'s own, so the page and its cart never disagree.
 *
 * Personal data inside (addresses, the messenger's number): [toString] shows ids only.
 *
 * @property messengers fixed: the shop's settings don't have them (CheckoutTexts).
 * @property giftWrap not offered yet: a gift is a card message and hidden prices, with no wrap and no cost (GiftNote).
 */
@Serializable
data class CheckoutContextHttpResponse(
    val cart: CartHttpResponse,
    val addresses: List<AddressHttpResponse>,
    val selectedAddressId: Long?,
    val messengers: List<MessengerOptionHttpResponse>,
    val selectedMessenger: String?,
    val messengerPhone: String,
    val shippingOptions: List<ShippingOptionQuoteHttpResponse>,
    val reservation: CartReservationHttpResponse,
    val consolidation: ReservationConsolidationHttpResponse?,
    val paymentGateways: List<PaymentGatewayHttpResponse>,
    val giftWrap: GiftWrapHttpResponse,
    val termsUrl: String,
) {
    override fun toString(): String = "CheckoutContextHttpResponse(cart=${cart.id}, selectedAddressId=$selectedAddressId)"

    companion object {
        /** The page of the shop's terms, which paying accepts («با پرداخت، قوانین کیوا رو می‌پذیری»). */
        const val TERMS_URL = "/pages/terms"

        fun from(context: CheckoutContextDto, issues: List<CartIssueDto>, urls: MediaUrls): CheckoutContextHttpResponse {
            val cart = CartHttpResponse.from(context.cart, issues, urls)
            return CheckoutContextHttpResponse(
                cart = cart,
                addresses = context.addresses.map(AddressHttpResponse::from),
                selectedAddressId = context.selectedAddressId,
                messengers = CheckoutTexts.messengers,
                selectedMessenger = context.selectedMessenger?.name,
                messengerPhone = context.messengerPhone,
                shippingOptions = cart.shippingOptions,
                reservation = cart.reservation,
                consolidation = cart.consolidation,
                paymentGateways = context.paymentGateways.map(PaymentGatewayHttpResponse::from),
                giftWrap = GiftWrapHttpResponse(available = false, price = 0),
                termsUrl = TERMS_URL,
            )
        }
    }
}

/** Contract schema `MessengerOption`: a messenger the photo before shipping can go to. [iconUrl] is `null`: the frontend has the icons. */
@Serializable
data class MessengerOptionHttpResponse(val channel: String, val name: String, val hint: String, val iconUrl: String?, val enabled: Boolean)

/**
 * Contract schema `PaymentGateway`. [available]: switched on and the app has an adapter for it; [isDefault]: the one
 * checkout selects first, always an available one (PaymentGateways.checkoutOptions). Without a [logoUrl] in the
 * settings (none yet) the frontend draws [initial] on [brandColor].
 */
@Serializable
data class PaymentGatewayHttpResponse(
    val code: String,
    val name: String,
    val logoUrl: String?,
    val brandColor: String,
    val initial: String,
    val isDefault: Boolean,
    val available: Boolean,
) {
    companion object {
        fun from(option: PaymentGateways.CheckoutOption) = PaymentGatewayHttpResponse(
            code = option.gateway.code.name,
            name = option.gateway.name,
            logoUrl = option.gateway.logoUrl,
            brandColor = option.gateway.brandColor,
            initial = option.gateway.initial,
            isDefault = option.isDefault,
            available = option.available,
        )
    }
}

/** The `giftWrap` of CheckoutContext, in toman. */
@Serializable
data class GiftWrapHttpResponse(val available: Boolean, val price: Long)
