package com.kiva.application.usecase.address

import com.kiva.application.dto.AddressDto
import com.kiva.common.railway.Result
import com.kiva.common.text.normalizeDigits
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.geo.repository.GeoRepository

/**
 * The contract's AddressInput as it arrived, raw: adding an address and editing one take the same fields.
 * Personal data: [toString] shows none of it.
 */
data class AddressFields(
    val title: String?,
    val provinceId: Int?,
    val cityId: Int?,
    val addressLine: String?,
    val plaque: String?,
    val unit: String?,
    val postalCode: String?,
    val recipientName: String?,
    val recipientPhone: String?,
    val isSelfRecipient: Boolean?,
    val lat: Double?,
    val lng: Double?,
) {
    override fun toString(): String = "AddressFields(provinceId=$provinceId, cityId=$cityId)"

    /** Every field checked at once (AddressInput). */
    fun toInput(): Result<AddressInput, AddressInput.Invalid> = AddressInput.makeNew(
        title, provinceId, cityId, addressLine, plaque, unit, postalCode, recipientName, recipientPhone, isSelfRecipient, lat, lng,
    )
}

/** Addresses with the names of their places, in two short reads: the 31 provinces and the few cities used. */
internal fun GeoRepository.addressDtosOf(addresses: List<Address>): List<AddressDto> {
    if (addresses.isEmpty()) return emptyList()
    val provinces = findAllProvinces().associateBy { it.id }
    val cities = findCities(addresses.map { it.cityId }.toSet()).associateBy { it.id }
    // The address table's foreign key to the city list keeps both lookups whole.
    return addresses.map { AddressDto.from(it, provinces.getValue(it.provinceId).name, cities.getValue(it.cityId).name) }
}

/** The order of the address book: the default first, then the rest oldest first, as the book keeps them. */
internal fun List<Address>.defaultFirst(): List<Address> = sortedByDescending { it.isDefault }

/**
 * The `{addressId}` path segment as an id; null when it is not a positive number. Such a segment can name no address,
 * so the use cases answer ADDRESS_NOT_FOUND, as for an address of someone else.
 */
internal fun addressIdOf(raw: String): AddressId? = raw.trim().normalizeDigits().toLongOrNull()?.let { AddressId.makeNew(it).getOrNull() }
