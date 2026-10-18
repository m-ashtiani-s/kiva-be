package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `POST /admin/orders/{orderCode}/notes`. Personal: [toString] hides the text. */
@Serializable
data class AdminNoteHttpRequest(val text: String? = null) {
    override fun toString(): String = "AdminNoteHttpRequest(***)"
}

/** The body of `PUT /admin/orders/{orderCode}/pre-shipment-media` (contract AdminPreShipmentMediaRequest). Every field nullable. */
@Serializable
data class AdminPreShipmentMediaHttpRequest(val items: List<Item>? = null) {
    @Serializable
    data class Item(val mediaId: String? = null, val orderItemId: Int? = null, val view: String? = null, val sortOrder: Int? = null)
}

/** The body of `POST /admin/orders/{orderCode}/pre-shipment-media/send`, all optional. [message] is the operator's: [toString] hides it. */
@Serializable
data class AdminSendPhotosHttpRequest(val manual: Boolean? = null, val channelOverride: String? = null, val message: String? = null) {
    override fun toString(): String = "AdminSendPhotosHttpRequest(manual=$manual, channelOverride=$channelOverride)"
}
