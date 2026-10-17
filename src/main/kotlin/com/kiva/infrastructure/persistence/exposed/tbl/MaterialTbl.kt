package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Materials. Created by V1__catalog.sql and V23__catalog_admin.sql (Exposed never creates tables); keep them in step. */
object MaterialTbl : LongIdTable(name = "material_tbl", columnName = "pId") {
    val slug = varchar("slug", 100).uniqueIndex()
    val name = varchar("name", 120)
    val family = enumerationByName<MaterialFamily>("family", 20)
    val description = text("description").nullable()
    val careInstructions = array<String>("careInstructions")

    /** The unique key on [slug] as V1 named it; the database names it when it refuses a save. */
    const val SLUG_KEY = "material_tbl_slug_key"

    /** The sequence behind [id] (the IDENTITY column), so an id can be taken before the row is written. */
    const val ID_SEQUENCE_OF = "pg_get_serial_sequence('material_tbl', 'pId')"
}
