package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Runs inside the use case's transaction; it never opens one itself. */
class ColorRepositoryImpl : ColorRepository {

    // pId breaks ties, so two colors with the same sortOrder still come back in a stable order.
    override fun findAll(): List<Color> =
        ColorTbl.selectAll()
            .orderBy(ColorTbl.sortOrder to SortOrder.ASC, ColorTbl.id to SortOrder.ASC)
            .map { it.toColor() }

    private fun ResultRow.toColor(): Color =
        Color.constructFromPersisted(
            key = ColorKey.constructFromPersisted(this[ColorTbl.colorKey]),
            name = ColorName.constructFromPersisted(this[ColorTbl.name]),
            hex = HexColor.constructFromPersisted(this[ColorTbl.hex]),
            isLight = this[ColorTbl.isLight],
            sortOrder = this[ColorTbl.sortOrder],
        )
}
