package com.kiva.domain.aggregate.material.repository

import com.kiva.domain.aggregate.material.model.valueobject.MaterialId

/** Takes the next material id (the identity of material_tbl), so a new material has its id before it is stored. */
interface MaterialIdGenerator {
    fun nextMaterialId(): MaterialId
}
