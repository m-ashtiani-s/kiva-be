package com.kiva.application.dto

import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily

/**
 * A material as the shop shows it in the filter and spec table, plus how many products use it (the shop counts those
 * in the shop, the panel every status).
 */
data class MaterialDto(
    val id: Long,
    val slug: String,
    val name: String,
    val family: MaterialFamily,
    val description: String?,
    val careInstructions: List<String>,
    val productCount: Int,
) {
    companion object {
        fun from(material: Material, productCount: Int): MaterialDto =
            MaterialDto(
                id = material.id.value,
                slug = material.slug.value,
                name = material.name.value,
                family = material.family,
                description = material.description?.value,
                careInstructions = material.careInstructions.items,
                productCount = productCount,
            )
    }
}
