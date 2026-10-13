package com.kiva.application.dto

import com.kiva.domain.aggregate.address.entity.Address
import java.time.Instant

/**
 * A saved address as the address book and checkout show it: the address, plus the names of its province and city.
 * Personal data: [toString] shows only the id.
 */
data class AddressDto(
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
    val lat: Double?,
    val lng: Double?,
    val isDefault: Boolean,
    val createdOn: Instant,
) {
    override fun toString(): String = "AddressDto(id=$id, isDefault=$isDefault)"

    companion object {
        fun from(address: Address, provinceName: String, cityName: String): AddressDto = AddressDto(
            id = address.id.value,
            title = address.title?.value,
            provinceId = address.provinceId.value,
            provinceName = provinceName,
            cityId = address.cityId.value,
            cityName = cityName,
            addressLine = address.line.value,
            plaque = address.plaque?.value,
            unit = address.unit?.value,
            postalCode = address.postalCode.value,
            recipientName = address.recipientName.value,
            recipientPhone = address.recipientPhone.value,
            isSelfRecipient = address.isSelfRecipient,
            lat = address.location?.lat,
            lng = address.location?.lng,
            isDefault = address.isDefault,
            createdOn = address.createdOn,
        )
    }
}
