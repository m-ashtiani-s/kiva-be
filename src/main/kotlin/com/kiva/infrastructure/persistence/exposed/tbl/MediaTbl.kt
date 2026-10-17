package com.kiva.infrastructure.persistence.exposed.tbl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** Uploaded files, one row each; the files are on the disk. Created by V22__media.sql (Exposed never creates tables); keep both in step. */
object MediaTbl : LongIdTable(name = "media_tbl", columnName = "pId") {
    val mediaId = varchar("mediaId", 26)
    val purpose = varchar("purpose", 20)
    val type = varchar("type", 5)
    val mimeType = varchar("mimeType", 20)
    val width = integer("width")
    val height = integer("height")
    val durationSec = integer("durationSec").nullable()
    val bytes = long("bytes")
    val blurhash = varchar("blurhash", 100).nullable()

    /** `[{width, path}]`, smallest first. */
    val variants = jsonb<JsonArray>("variants", Json)
    val posterPath = varchar("posterPath", 200).nullable()
    val alt = varchar("alt", 200).nullable()
    val storageDir = varchar("storageDir", 100)
    val uploadedBy = long("uploadedBy")
    val uploadedOn = timestampWithTimeZone("uploadedOn")
    val attachedOn = timestampWithTimeZone("attachedOn").nullable()
}
