package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AddressDto
import kotlinx.serialization.Serializable

/**
 * Contract schema `Address`. Personal data: [toString] shows only the id.
 *
 * @property fullText the whole address in one line, for checkout and the courier: «استان، شهر، آدرس، پلاک ۱۲، واحد ۴».
 * @property createdAt when it was saved, ISO-8601 in UTC.
 */
@Serializable
data class AddressHttpResponse(
    val id: Long,
    val title: String?,
    val provinceId: Int,
    val provinceName: String,
    val cityId: Int,
    val cityName: String,
    val addressLine: String,
    val plaque: String?,
    val unit: String?,
    val postalCode: String,
    val recipientName: String,
    val recipientPhone: String,
    val isSelfRecipient: Boolean,
    val isDefault: Boolean,
    val location: LocationHttpResponse?,
    val fullText: String,
    val createdAt: String,
) {
    override fun toString(): String = "AddressHttpResponse(id=$id)"

    companion object {
        fun from(dto: AddressDto) = AddressHttpResponse(
            id = dto.id,
            title = dto.title,
            provinceId = dto.provinceId,
            provinceName = dto.provinceName,
            cityId = dto.cityId,
            cityName = dto.cityName,
            addressLine = dto.addressLine,
            plaque = dto.plaque,
            unit = dto.unit,
            postalCode = dto.postalCode,
            recipientName = dto.recipientName,
            recipientPhone = dto.recipientPhone,
            isSelfRecipient = dto.isSelfRecipient,
            isDefault = dto.isDefault,
            location = dto.lat?.let { lat -> dto.lng?.let { lng -> LocationHttpResponse(lat, lng) } },
            fullText = AddressTexts.fullText(dto.provinceName, dto.cityName, dto.addressLine, dto.plaque, dto.unit),
            createdAt = dto.createdOn.toString(),
        )
    }
}

@Serializable
data class LocationHttpResponse(val lat: Double, val lng: Double) {
    override fun toString(): String = "LocationHttpResponse(***)"
}

/** The Persian texts of an address, made here because the domain only gives values. */
object AddressTexts {
    /**
     * «تهران، تهران، خیابان ولیعصر، کوچه نسترن، پلاک ۱۲، واحد ۴»: the plaque and unit only when given, with Persian
     * digits like every text made for display. The address line is shown exactly as the customer wrote it. An erased
     * order's address (no line) is «استان، شهر» only.
     */
    fun fullText(provinceName: String, cityName: String, addressLine: String?, plaque: String?, unit: String?): String =
        listOfNotNull(
            provinceName,
            cityName,
            addressLine,
            plaque?.let { "پلاک ${it.persianDigits()}" },
            unit?.let { "واحد ${it.persianDigits()}" },
        ).joinToString("، ")

    private fun String.persianDigits(): String = map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
}
