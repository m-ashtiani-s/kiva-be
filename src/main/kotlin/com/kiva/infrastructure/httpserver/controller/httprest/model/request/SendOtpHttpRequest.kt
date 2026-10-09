package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The contract's SendOtpRequest, as it arrives. Raw: the use case checks it.
 *
 * @property purpose LOGIN or left out; anything else is refused (other codes have endpoints of their own).
 */
@Serializable
data class SendOtpHttpRequest(val phone: String, val purpose: String? = null)
