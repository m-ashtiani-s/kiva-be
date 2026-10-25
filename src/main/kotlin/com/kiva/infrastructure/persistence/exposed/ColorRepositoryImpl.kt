package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.repository.StillReferencedException
import com.kiva.common.repository.UniqueTakenException
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.infrastructure.common.persistence.isForeignKeyRefusal
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Runs inside the use case's transaction; it never opens one itself. */
class ColorRepositoryImpl : ColorRepository {

    // pId breaks ties, so two colors with the same sortOrder still come back in a stable order.
    override fun findAll(): List<Color> =
        ColorTbl.selectAll()
            .orderBy(ColorTbl.sortOrder to SortOrder.ASC, ColorTbl.id to SortOrder.ASC)
            .map { it.toColor() }

    override fun findByKey(key: ColorKey): Color? =
        ColorTbl.selectAll()
            .where { ColorTbl.colorKey eq key.value }
            .singleOrNull()
            ?.toColor()

    override fun maxSortOrder(): Int? {
        val largest = ColorTbl.sortOrder.max()
        return ColorTbl.select(largest).single()[largest]
    }

    override fun insert(color: Color) {
        try {
            ColorTbl.insert {
                it[colorKey] = color.key.value
                it[name] = color.name.value
                it[hex] = color.hex.value
                it[isLight] = color.isLight
                it[sortOrder] = color.sortOrder
            }
        } catch (e: ExposedSQLException) {
            // The unique key on the key is the last word on it; any other refusal is a bug and goes up as it is.
            if (e.refusedConstraint() == ColorTbl.KEY_KEY) throw UniqueTakenException("color key ${color.key.value}", e)
            throw e
        }
    }

    override fun update(color: Color) {
        ColorTbl.update({ ColorTbl.colorKey eq color.key.value }) {
            it[name] = color.name.value
            it[hex] = color.hex.value
            it[isLight] = color.isLight
            it[sortOrder] = color.sortOrder
        }
    }

    override fun delete(key: ColorKey) {
        try {
            ColorTbl.deleteWhere { ColorTbl.colorKey eq key.value }
        } catch (e: ExposedSQLException) {
            if (e.isForeignKeyRefusal()) throw StillReferencedException("color ${key.value}", e)
            throw e
        }
    }

    private fun ResultRow.toColor(): Color =
        Color.constructFromPersisted(
            key = ColorKey.constructFromPersisted(this[ColorTbl.colorKey]),
            name = ColorName.constructFromPersisted(this[ColorTbl.name]),
            hex = HexColor.constructFromPersisted(this[ColorTbl.hex]),
            isLight = this[ColorTbl.isLight],
            sortOrder = this[ColorTbl.sortOrder],
        )
}
