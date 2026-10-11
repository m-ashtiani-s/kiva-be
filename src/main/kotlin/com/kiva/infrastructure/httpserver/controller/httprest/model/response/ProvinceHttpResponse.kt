package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CityDto
import com.kiva.application.dto.ProvinceDto
import kotlinx.serialization.Serializable

/** Contract schema `Province`. [cities] is null when the client asked without them (`includeCities=false`). */
@Serializable
data class ProvinceHttpResponse(
    val id: Int,
    val name: String,
    val cities: List<CityHttpResponse>?,
) {
    companion object {
        fun from(dto: ProvinceDto) = ProvinceHttpResponse(dto.id, dto.name, dto.cities?.map(CityHttpResponse::from))
    }
}

/** Contract schema `City`. */
@Serializable
data class CityHttpResponse(
    val id: Int,
    val name: String,
    val provinceId: Int,
) {
    companion object {
        fun from(dto: CityDto) = CityHttpResponse(dto.id, dto.name, dto.provinceId)
    }
}
