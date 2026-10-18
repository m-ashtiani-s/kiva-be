package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `POST /admin/orders/{orderCode}/notes`. Personal: [toString] hides the text. */
@Serializable
data class AdminNoteHttpRequest(val text: String? = null) {
    override fun toString(): String = "AdminNoteHttpRequest(***)"
}
