package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** The palette. Created by V1__catalog.sql (Exposed never creates tables); keep both in step. */
object ColorTbl : LongIdTable(name = "color_tbl", columnName = "pId") {
    val colorKey = varchar("colorKey", 31).uniqueIndex()
    val name = varchar("name", 50)
    val hex = varchar("hex", 7)
    val isLight = bool("isLight")
    val sortOrder = integer("sortOrder")
}
