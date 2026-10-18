package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/**
 * The body of `POST /me/orders/{orderCode}/media-feedback` (contract PreShipmentFeedbackRequest). Every field nullable;
 * the change request's fields are read from phase 3 on. [note] is the customer's: [toString] hides it.
 */
@Serializable
data class PreShipmentFeedbackHttpRequest(
    val decision: String? = null,
    val changeType: String? = null,
    val orderItemId: Int? = null,
    val desiredVariantId: Long? = null,
    val note: String? = null,
) {
    override fun toString(): String = "PreShipmentFeedbackHttpRequest(decision=$decision, changeType=$changeType)"
}

/** The body of `POST /me/orders/{orderCode}/cancel` (contract CancelOrderRequest). [note] is never logged. */
@Serializable
data class CancelOrderHttpRequest(val reason: String? = null, val note: String? = null) {
    override fun toString(): String = "CancelOrderHttpRequest(reason=$reason)"
}
