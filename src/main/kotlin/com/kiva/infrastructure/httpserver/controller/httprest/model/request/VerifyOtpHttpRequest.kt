package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The contract's VerifyOtpRequest, as it arrives. Raw: the use case checks it.
 *
 * @property code 5 digits; Persian digits are fine.
 * @property guestCartToken for merging a guest's cart, from the cart phase; read and ignored until then.
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

    // The code is a secret and the phone personal data, if this is ever printed.
    override fun toString(): String = "VerifyOtpHttpRequest(phone=<hidden>, code=<hidden>, device=$device)"
}
