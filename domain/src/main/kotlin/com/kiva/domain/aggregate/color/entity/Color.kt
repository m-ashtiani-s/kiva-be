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
 * Its identity is [key]: two colors with the same key are the same color, whatever their other values. The key never
 * changes: stored events and category icons name it, without a cascade.
 *
 * Rules it keeps: none between its fields. It can't know whether its key is free, nor what uses it: the use case
 * counts that before a color leaves the palette.
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
    /** Everything but the key, at once: `PUT` replaces them all. */
    fun change(name: ColorName, hex: HexColor, isLight: Boolean, sortOrder: Int): Color = Color(key, name, hex, isLight, sortOrder)

    /** Whether [other] holds exactly the same values (equality is by [key] only). */
    fun sameValuesAs(other: Color): Boolean = name == other.name && hex == other.hex && isLight == other.isLight && sortOrder == other.sortOrder

    override fun equals(other: Any?): Boolean = other is Color && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "Color(key=${key.value})"

    companion object {
        /** A new color of the palette, from the panel. */
        fun create(key: ColorKey, name: ColorName, hex: HexColor, isLight: Boolean, sortOrder: Int): Color = Color(key, name, hex, isLight, sortOrder)

        fun constructFromPersisted(key: ColorKey, name: ColorName, hex: HexColor, isLight: Boolean, sortOrder: Int): Color =
            Color(key, name, hex, isLight, sortOrder)
    }
}
