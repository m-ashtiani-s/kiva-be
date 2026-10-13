package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Iran's cities. Created and filled by V11__geo.sql (Exposed never creates tables); keep both in step. */
object CityTbl : LongIdTable(name = "city_tbl", columnName = "pId") {
    val cityId = integer("cityId").uniqueIndex()
    val provinceId = integer("provinceId")
    val name = varchar("name", 50)
    val sortOrder = integer("sortOrder")
}
