package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The contract's PlaceOrderRequest, as it arrives. Every field may be missing, so the use case names each wrong field
 * at once instead of the whole body being refused as unreadable; `acceptTerms` not sent is not accepted (422). The
 * defaults are the contract's, and the request's fingerprint is written with them, so a field left out and the same
 * field sent with its default are one request. Personal data: [toString] shows none of it.
 */
@Serializable
data class PlaceOrderHttpRequest(
    val addressId: Long? = null,
    val newAddress: AddressHttpRequest? = null,
    val saveNewAddress: Boolean = true,
    val preShipmentMessenger: PreShipmentMessengerHttpRequest? = null,
    val shippingMethod: String? = null,
    val reserve: Boolean? = null,
    val paymentGateway: String? = null,
    val customerNote: String? = null,
    val gift: GiftHttpRequest? = null,
    val expectedPayable: Long? = null,
    val acceptTerms: Boolean = false,
) {
    override fun toString(): String =
        "PlaceOrderHttpRequest(addressId=$addressId, newAddress=${newAddress != null}, shippingMethod=$shippingMethod, paymentGateway=$paymentGateway)"
}

/** «عکس کیفت رو قبل از ارسال کجا بفرستیم؟». Personal data: [toString] shows the channel only. */
@Serializable
data class PreShipmentMessengerHttpRequest(
    val channel: String? = null,
    val phone: String? = null,
    val username: String? = null,
    val note: String? = null,
    val saveAsDefault: Boolean = true,
) {
    override fun toString(): String = "PreShipmentMessengerHttpRequest(channel=$channel)"
}

/** «سفارش هدیه». [wrap] is read and ignored while the shop offers no wrap (`CheckoutContext.giftWrap`). */
@Serializable
data class GiftHttpRequest(val wrap: Boolean = false, val message: String? = null, val hidePrices: Boolean = true) {
    override fun toString(): String = "GiftHttpRequest(hidePrices=$hidePrices)"
}
