package com.kiva.domain.aggregate.color.repository

import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey

interface ColorRepository {
    /** The whole palette, in palette order ([Color.sortOrder]). */
    fun findAll(): List<Color>

    fun findByKey(key: ColorKey): Color?
}
