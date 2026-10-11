package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.address.AddressFields
import kotlinx.serialization.Serializable

/**
 * The contract's AddressInput, as it arrives. Every field may be missing so the use case can name each wrong field at
 * once, instead of the whole body being refused as unreadable. Personal data: [toString] shows none of it.
 */
@Serializable
data class AddressHttpRequest(
    val title: String? = null,
    val provinceId: Int? = null,
    val cityId: Int? = null,
    val addressLine: String? = null,
    val plaque: String? = null,
    val unit: String? = null,
    val postalCode: String? = null,
    val recipientName: String? = null,
    val recipientPhone: String? = null,
    val isSelfRecipient: Boolean? = null,
    val location: LocationHttpRequest? = null,
    val setAsDefault: Boolean? = null,
) {
    override fun toString(): String = "AddressHttpRequest(provinceId=$provinceId, cityId=$cityId)"

    fun toFields(): AddressFields = AddressFields(
        title, provinceId, cityId, addressLine, plaque, unit, postalCode, recipientName, recipientPhone, isSelfRecipient,
        lat = location?.lat, lng = location?.lng,
    )
}

/** A point picked on the map. */
@Serializable
data class LocationHttpRequest(val lat: Double? = null, val lng: Double? = null) {
    override fun toString(): String = "LocationHttpRequest(***)"
}
