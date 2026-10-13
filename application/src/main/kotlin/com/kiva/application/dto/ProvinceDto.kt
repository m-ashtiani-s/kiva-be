package com.kiva.application.dto

import com.kiva.domain.aggregate.geo.entity.Province

/** A province for the address form, with its cities when they were asked for (null when not). */
data class ProvinceDto(
    val id: Int,
    val name: String,
    val cities: List<CityDto>?,
) {
    companion object {
        fun from(province: Province, cities: List<CityDto>?): ProvinceDto = ProvinceDto(province.id.value, province.name, cities)
    }
}
