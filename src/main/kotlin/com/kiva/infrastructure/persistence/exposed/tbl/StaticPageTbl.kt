package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** The site's static pages. Created by V31__content_basics.sql (Exposed never creates tables); keep them in step. */
object StaticPageTbl : LongIdTable(name = "staticpage_tbl", columnName = "pId") {
    val slug = varchar("slug", 100).uniqueIndex()
    val title = varchar("title", 120)
    /** The sanitized HTML. */
    val content = text("content")
    val seoTitle = varchar("seoTitle", 70).nullable()
    val seoDescription = varchar("seoDescription", 170).nullable()
    val seoNoIndex = bool("seoNoIndex")
    val updatedOn = timestampWithTimeZone("updatedOn")
    val updatedBy = long("updatedBy")
}
