package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ColorDto
import kotlinx.serialization.Serializable

/** Contract schema `Color`. [productCount] only in `/colors` (and facets later); left out on a product's swatches. */
@Serializable
data class ColorHttpResponse(
    val key: String,
    val name: String,
    val hex: String,
    val isLight: Boolean,
    val sortOrder: Int,
    val productCount: Int? = null,
) {
    companion object {
        fun from(dto: ColorDto) = ColorHttpResponse(dto.key, dto.name, dto.hex, dto.isLight, dto.sortOrder, dto.productCount)
    }
}
