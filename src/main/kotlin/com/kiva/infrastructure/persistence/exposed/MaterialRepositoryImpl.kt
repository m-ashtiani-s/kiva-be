package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.repository.StillReferencedException
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.model.valueobject.MaterialNote
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.infrastructure.common.persistence.isForeignKeyRefusal
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Runs inside the use case's transaction; it never opens one itself. */
class MaterialRepositoryImpl : MaterialRepository {

    override fun findAll(): List<Material> =
        MaterialTbl.selectAll()
            .orderBy(MaterialTbl.id to SortOrder.ASC)
            .map { it.toMaterial() }

    override fun findById(id: MaterialId): Material? =
        MaterialTbl.selectAll()
            .where { MaterialTbl.id eq id.value }
            .singleOrNull()
            ?.toMaterial()

    override fun findBySlug(slug: Slug): Material? =
        MaterialTbl.selectAll()
            .where { MaterialTbl.slug eq slug.value }
            .singleOrNull()
            ?.toMaterial()

    override fun insert(material: Material) {
        writing(material) {
            MaterialTbl.insert {
                it[id] = material.id.value
                it.fill(material)
            }
        }
    }

    override fun update(material: Material) {
        writing(material) {
            MaterialTbl.update({ MaterialTbl.id eq material.id.value }) { it.fill(material) }
        }
    }

    override fun delete(id: MaterialId) {
        try {
            MaterialTbl.deleteWhere { MaterialTbl.id eq id.value }
        } catch (e: ExposedSQLException) {
            if (e.isForeignKeyRefusal()) throw StillReferencedException("material ${id.value}", e)
            throw e
        }
    }

    /** The unique key on the slug is the last word on it; any other refusal is a bug and goes up as it is. */
    private fun writing(material: Material, write: () -> Unit) {
        try {
            write()
        } catch (e: ExposedSQLException) {
            if (e.refusedConstraint() == MaterialTbl.SLUG_KEY) throw UniqueTakenException("material slug ${material.slug.value}", e)
            throw e
        }
    }

    private fun UpdateBuilder<*>.fill(material: Material) {
        this[MaterialTbl.slug] = material.slug.value
        this[MaterialTbl.name] = material.name.value
        this[MaterialTbl.family] = material.family
        this[MaterialTbl.description] = material.description?.value
        this[MaterialTbl.careInstructions] = material.careInstructions.items
    }

    private fun ResultRow.toMaterial(): Material =
        Material.constructFromPersisted(
            id = MaterialId.constructFromPersisted(this[MaterialTbl.id].value),
            slug = Slug.constructFromPersisted(this[MaterialTbl.slug]),
            name = MaterialName.constructFromPersisted(this[MaterialTbl.name]),
            family = this[MaterialTbl.family],
            description = this[MaterialTbl.description]?.let(MaterialNote::constructFromPersisted),
            careInstructions = CareInstructions.constructFromPersisted(this[MaterialTbl.careInstructions]),
        )
}

