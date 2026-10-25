package com.kiva.application.dto

import com.kiva.domain.aggregate.color.entity.Color

/**
 * A palette color as the shop shows it: the swatch plus how many products in the shop come in it ([productCount] is
 * null where the contract leaves it out: the panel's answers to its own writes).
 */
data class ColorDto(
    val key: String,
    val name: String,
    val hex: String,
    val isLight: Boolean,
    val sortOrder: Int,
    val productCount: Int?,
) {
    companion object {
        fun from(color: Color, productCount: Int?): ColorDto =
            ColorDto(color.key.value, color.name.value, color.hex.value, color.isLight, color.sortOrder, productCount)
    }
}
