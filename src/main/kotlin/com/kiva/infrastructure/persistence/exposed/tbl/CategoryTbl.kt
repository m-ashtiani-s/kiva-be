package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.product.model.valueobject.BagType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Product categories. Created by V1__catalog.sql and V23__catalog_admin.sql (Exposed never creates tables); keep them in step. */
object CategoryTbl : LongIdTable(name = "category_tbl", columnName = "pId") {
    val slug = varchar("slug", 100).uniqueIndex()
    val name = varchar("name", 100)
    val description = text("description").nullable()
    val iconBagType = enumerationByName<BagType>("iconBagType", 20)
    val iconColorKey = varchar("iconColorKey", 31).references(ColorTbl.colorKey)
    val sortOrder = integer("sortOrder")
    val showInMenu = bool("showInMenu")
    val imageMediaId = varchar("imageMediaId", 26).nullable()
    val seoTitle = varchar("seoTitle", 70).nullable()
    val seoDescription = varchar("seoDescription", 170).nullable()
    val seoNoIndex = bool("seoNoIndex")

    /** The unique key on [slug] as V1 named it; the database names it when it refuses a save. */
    const val SLUG_KEY = "category_tbl_slug_key"

    /** The sequence behind [id] (the IDENTITY column), so an id can be taken before the row is written. */
    const val ID_SEQUENCE_OF = "pg_get_serial_sequence('category_tbl', 'pId')"
}
