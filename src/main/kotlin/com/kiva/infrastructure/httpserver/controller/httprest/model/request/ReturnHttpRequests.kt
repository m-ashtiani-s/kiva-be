package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The body of `POST /me/orders/{orderCode}/returns` (contract CreateReturnRequest). Every field nullable, so a missing one
 * is reported with the rest; an old client's `iban` is ignored (decision 7). [description] is the customer's: [toString]
 * hides it.
 */
@Serializable
data class CreateReturnHttpRequest(
    val items: List<ReturnItemHttpRequest?>? = null,
    val reason: String? = null,
    val description: String? = null,
    val mediaIds: List<String?>? = null,
    val refundMethod: String? = null,
) {
    override fun toString(): String = "CreateReturnHttpRequest(items=${items?.size}, reason=$reason, mediaIds=${mediaIds?.size})"
}

/** One line of CreateReturnRequest.items. */
@Serializable
data class ReturnItemHttpRequest(val orderItemId: Int? = null, val quantity: Int? = null)
