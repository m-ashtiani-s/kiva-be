package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** The announcement bar. Created by V31__content_basics.sql (Exposed never creates tables); keep them in step. */
object AnnouncementTbl : LongIdTable(name = "announcement_tbl", columnName = "pId") {
    /** The announcement's own id, kept across saves (the contract's `Announcement.id`). */
    val announcementId = long("announcementId").uniqueIndex()
    val icon = varchar("icon", 30).nullable()
    val text = varchar("text", 200)
    val url = varchar("url", 300).nullable()
    val sortOrder = integer("sortOrder")
    val startsAt = timestampWithTimeZone("startsAt").nullable()
    val endsAt = timestampWithTimeZone("endsAt").nullable()

    /** V31's sequence of announcement ids. */
    const val ID_SEQUENCE = "announcementid_seq"
}
