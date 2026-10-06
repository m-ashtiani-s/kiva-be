package com.kiva.domain.aggregate.color.entity

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor

/**
 * One color of the shop's palette (یاسی، مشکی، ...). Every product variant is one of these colors.
 *
 * Reference data, stored in a plain table rather than as events: the palette changes rarely,
 * has no lifecycle, and nobody needs its history.
 *
 * Its identity is [key]: two colors with the same key are the same color, whatever their other values.
 *
 * @property hex the swatch color.
 * @property isLight a light swatch (white, cream, ...), so the client draws the selected tick in a dark color.
 * @property sortOrder position in the palette, smallest first.
 */
class Color private constructor(
    val key: ColorKey,
    val name: ColorName,
    val hex: HexColor,
    val isLight: Boolean,
    val sortOrder: Int,
) {
    override fun equals(other: Any?): Boolean = other is Color && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "Color(key=${key.value})"

    companion object {
        fun constructFromPersisted(key: ColorKey, name: ColorName, hex: HexColor, isLight: Boolean, sortOrder: Int): Color =
            Color(key, name, hex, isLight, sortOrder)
    }
}
