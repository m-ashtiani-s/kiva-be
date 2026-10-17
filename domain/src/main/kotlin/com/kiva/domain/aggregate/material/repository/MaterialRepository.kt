package com.kiva.domain.aggregate.material.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId

/**
 * The materials, kept as rows (material_tbl). Runs inside the caller's transaction.
 *
 * [insert] and [update] throw com.kiva.common.repository.UniqueTakenException when another material took the slug
 * after the caller checked; [delete] throws com.kiva.common.repository.StillReferencedException when a product got the
 * material after the caller counted none.
 */
interface MaterialRepository {
    /** Every material, in the order they were added (materials have no display order of their own). */
    fun findAll(): List<Material>

    fun findById(id: MaterialId): Material?

    fun findBySlug(slug: Slug): Material?

    fun insert(material: Material)

    fun update(material: Material)

    fun delete(id: MaterialId)
}
