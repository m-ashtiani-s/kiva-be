package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.product.model.valueobject.BagType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Product categories. Created by V1__catalog.sql (Exposed never creates tables); keep both in step. */
object CategoryTbl : LongIdTable(name = "category_tbl", columnName = "pId") {
    val slug = varchar("slug", 100).uniqueIndex()
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val iconBagType = enumerationByName<BagType>("iconBagType", 20)
    val iconColorKey = varchar("iconColorKey", 31).references(ColorTbl.colorKey)
    val sortOrder = integer("sortOrder")
    val showInMenu = bool("showInMenu")
}
