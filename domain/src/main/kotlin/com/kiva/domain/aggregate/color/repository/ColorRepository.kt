package com.kiva.domain.aggregate.color.repository

import com.kiva.domain.aggregate.color.entity.Color

interface ColorRepository {
    /** The whole palette, in palette order ([Color.sortOrder]). */
    fun findAll(): List<Color>
}
