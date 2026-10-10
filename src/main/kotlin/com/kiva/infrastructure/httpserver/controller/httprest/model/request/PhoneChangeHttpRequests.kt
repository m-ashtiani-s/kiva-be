package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `POST /me/phone-change/request`, as it arrives. */
@Serializable
data class PhoneChangeRequestHttpRequest(val newPhone: String) {
    // A phone is personal data, if this is ever printed.
    override fun toString(): String = "PhoneChangeRequestHttpRequest(newPhone=<hidden>)"
}

/**
 * The body of `POST /me/phone-change/verify`, as it arrives.
 *
 * @property currentPhoneCode the code sent to the account's current phone; [newPhoneCode] the one sent to the new phone.
 * @property device the client's name for the device of the new session, for display only.
 */
@Serializable
data class PhoneChangeVerifyHttpRequest(
    val newPhone: String,
    val currentPhoneCode: String,
    val newPhoneCode: String,
    val device: VerifyOtpHttpRequest.Device? = null,
) {
    // A phone is personal data and the codes are secrets, if this is ever printed.
    override fun toString(): String = "PhoneChangeVerifyHttpRequest(newPhone=<hidden>, codes=<hidden>, device=$device)"
}
