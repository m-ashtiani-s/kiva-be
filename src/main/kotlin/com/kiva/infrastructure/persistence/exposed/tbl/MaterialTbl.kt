package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Materials. Created by V1__catalog.sql (Exposed never creates tables); keep both in step. */
object MaterialTbl : LongIdTable(name = "material_tbl", columnName = "pId") {
    val slug = varchar("slug", 100).uniqueIndex()
    val name = varchar("name", 120)
    val family = enumerationByName<MaterialFamily>("family", 20)
    val description = text("description").nullable()
    val careInstructions = array<String>("careInstructions")
}
