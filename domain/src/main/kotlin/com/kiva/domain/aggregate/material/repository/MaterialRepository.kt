package com.kiva.domain.aggregate.material.repository

import com.kiva.domain.aggregate.material.entity.Material

interface MaterialRepository {
    /** Every material, in the order they were added (materials have no display order of their own). */
    fun findAll(): List<Material>
}
