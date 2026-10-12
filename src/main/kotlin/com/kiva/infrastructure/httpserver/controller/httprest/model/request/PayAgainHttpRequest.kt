package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The body of `POST /payments/{paymentId}/retry` and `POST /me/orders/{orderCode}/payments`: the gateway to pay through
 * this time, as it arrives; null (or no body at all) keeps the gateway of before. The use case names a wrong one.
 */
@Serializable
data class PayAgainHttpRequest(val gateway: String? = null)
