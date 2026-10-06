package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Runs inside the use case's transaction; it never opens one itself. */
class MaterialRepositoryImpl : MaterialRepository {

    override fun findAll(): List<Material> =
        MaterialTbl.selectAll()
            .orderBy(MaterialTbl.id to SortOrder.ASC)
            .map { it.toMaterial() }

    private fun ResultRow.toMaterial(): Material =
        Material.constructFromPersisted(
            id = MaterialId.constructFromPersisted(this[MaterialTbl.id].value),
            slug = Slug.constructFromPersisted(this[MaterialTbl.slug]),
            name = MaterialName.constructFromPersisted(this[MaterialTbl.name]),
            family = this[MaterialTbl.family],
            description = this[MaterialTbl.description],
            careInstructions = this[MaterialTbl.careInstructions],
        )
}
