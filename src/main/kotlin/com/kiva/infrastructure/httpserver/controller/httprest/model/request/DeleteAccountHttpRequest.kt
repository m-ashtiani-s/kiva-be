package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `DELETE /me`, as it arrives: the DELETE_ACCOUNT code, and why (optional). */
@Serializable
data class DeleteAccountHttpRequest(val otpCode: String? = null, val reason: String? = null) {
    // The code is a secret and the reason may say anything, if this is ever printed.
    override fun toString(): String = "DeleteAccountHttpRequest(otpCode=<hidden>, reason=<hidden>)"
}
