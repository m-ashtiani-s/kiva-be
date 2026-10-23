package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `POST /contact/messages` (contract ContactMessageRequest). Personal fields: [toString] hides them. */
@Serializable
data class ContactMessageHttpRequest(
    val topic: String? = null,
    val fullName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val orderCode: String? = null,
    val message: String? = null,
) {
    override fun toString(): String = "ContactMessageHttpRequest(topic=$topic, orderCode=$orderCode)"
}
