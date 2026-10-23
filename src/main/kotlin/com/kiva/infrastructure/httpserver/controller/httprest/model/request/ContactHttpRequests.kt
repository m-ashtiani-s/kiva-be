package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.common.patch.Patch
import com.kiva.infrastructure.common.http.PatchSerializer
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

/**
 * The body of `PATCH /admin/contact-messages/{ticketCode}`: every field optional. [internalNote] sent as null clears it.
 * Texts: [toString] hides them.
 */
@Serializable
data class UpdateContactMessageHttpRequest(
    val status: String? = null,
    val reply: String? = null,
    val replyChannel: String? = null,
    @Serializable(with = PatchSerializer::class) val internalNote: Patch<String?> = Patch.Unchanged,
) {
    override fun toString(): String = "UpdateContactMessageHttpRequest(status=$status, replyChannel=$replyChannel)"
}
