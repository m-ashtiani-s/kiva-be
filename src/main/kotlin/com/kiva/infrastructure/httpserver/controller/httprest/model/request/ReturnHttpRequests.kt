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

/** The body of `POST /admin/returns/{returnId}/decision` (contract AdminReturnDecisionRequest). Staff's texts: [toString] hides them. */
@Serializable
data class AdminReturnDecisionHttpRequest(
    val approve: Boolean? = null,
    val shippingPaidBy: String? = null,
    val instructions: String? = null,
    val rejectionReason: String? = null,
) {
    override fun toString(): String = "AdminReturnDecisionHttpRequest(approve=$approve, shippingPaidBy=$shippingPaidBy)"
}

/** The body of `PUT /admin/returns/{returnId}/pickup` (contract AdminReturnPickupRequest). The note: [toString] hides it. */
@Serializable
data class AdminReturnPickupHttpRequest(
    val carrier: String? = null,
    val scheduledFor: String? = null,
    val trackingCode: String? = null,
    val note: String? = null,
    val notifyCustomer: Boolean? = null,
) {
    override fun toString(): String = "AdminReturnPickupHttpRequest(carrier=$carrier, scheduledFor=$scheduledFor, notifyCustomer=$notifyCustomer)"
}
