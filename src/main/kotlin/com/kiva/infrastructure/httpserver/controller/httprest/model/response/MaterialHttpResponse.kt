package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.MaterialDto
import kotlinx.serialization.Serializable

/** Contract schema `Material`. */
@Serializable
data class MaterialHttpResponse(
    val id: Long,
    val slug: String,
    val name: String,
    val family: String,
    val description: String? = null,
    val careInstructions: List<String>,
    val productCount: Int,
) {
    companion object {
        fun from(dto: MaterialDto) = MaterialHttpResponse(
            id = dto.id,
            slug = dto.slug,
            name = dto.name,
            family = dto.family.name,
            description = dto.description,
            careInstructions = dto.careInstructions,
            productCount = dto.productCount,
        )
    }
}
