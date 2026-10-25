package com.kiva.domain.aggregate.color.repository

import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey

/**
 * The palette, kept as rows (color_tbl). Runs inside the caller's transaction.
 *
 * [insert] throws com.kiva.common.repository.UniqueTakenException when another color took the key after the caller
 * checked; [delete] throws com.kiva.common.repository.StillReferencedException when something got the color after the
 * caller counted none.
 */
interface ColorRepository {
    /** The whole palette, in palette order ([Color.sortOrder], then when it was added). */
    fun findAll(): List<Color>

    fun findByKey(key: ColorKey): Color?

    /** The largest sortOrder in the palette, or null when it is empty: a new color goes after it. */
    fun maxSortOrder(): Int?

    fun insert(color: Color)

    fun update(color: Color)

    fun delete(key: ColorKey)
}
