package com.kiva.application.dto

import com.kiva.domain.aggregate.geo.entity.City

/** A city for the address form's city list. */
data class CityDto(
    val id: Int,
    val name: String,
    val provinceId: Int,
) {
    companion object {
        fun from(city: City): CityDto = CityDto(city.id.value, city.name, city.provinceId.value)
    }
}
