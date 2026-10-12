package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.application.port.IdempotencyScope
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** Requests done with an Idempotency-Key. Created by V17__idempotency.sql (Exposed never creates tables); keep both in step. */
object IdempotencyKeyTbl : LongIdTable(name = "idempotencykey_tbl", columnName = "pId") {
    val userId = long("userId").references(UserTbl.id)
    val scope = enumerationByName<IdempotencyScope>("scope", 20)
    val key = javaUUID("key")
    val requestHash = binary("requestHash", 32)
    val outcome = jsonb<Map<String, String>>("outcome", Json)
    val createdOn = timestampWithTimeZone("createdOn")

    init {
        uniqueIndex("idempotencykey_tbl_key", userId, scope, key)
    }
}
