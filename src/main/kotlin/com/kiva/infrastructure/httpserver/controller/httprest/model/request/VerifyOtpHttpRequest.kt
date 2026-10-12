package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The contract's VerifyOtpRequest, as it arrives. Raw: the use case checks it.
 *
 * @property code 5 digits; Persian digits are fine.
 * @property guestCartToken the guest's cart to merge into the customer's after the login; one that reaches no cart is
 *   ignored and never spoils the login. Like the code, never printed.
 * @property device the client's name for the device, for display only.
 */
@Serializable
data class VerifyOtpHttpRequest(
    val phone: String,
    val code: String,
    val guestCartToken: String? = null,
    val device: Device? = null,
) {
    @Serializable
    data class Device(val name: String? = null)

    // The code and the cart token are secrets and the phone personal data, if this is ever printed.
    override fun toString(): String = "VerifyOtpHttpRequest(phone=<hidden>, code=<hidden>, device=$device)"
}
