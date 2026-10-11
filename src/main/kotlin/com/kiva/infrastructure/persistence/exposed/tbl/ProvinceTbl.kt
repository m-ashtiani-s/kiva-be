package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Iran's provinces. Created and filled by V11__geo.sql (Exposed never creates tables); keep both in step. */
object ProvinceTbl : LongIdTable(name = "province_tbl", columnName = "pId") {
    val provinceId = integer("provinceId").uniqueIndex()
    val name = varchar("name", 50).uniqueIndex()
    val sortOrder = integer("sortOrder")
}
