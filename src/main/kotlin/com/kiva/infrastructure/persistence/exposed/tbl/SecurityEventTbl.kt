package com.kiva.infrastructure.persistence.exposed.tbl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The append-only security log: rows are only ever inserted (a trigger refuses changes). Created by
 * V9__otp_and_security_events.sql (Exposed never creates tables); keep both in step.
 */
object SecurityEventTbl : LongIdTable(name = "securityevent_tbl", columnName = "pId") {
    val occurredOn = timestampWithTimeZone("occurredOn")
    val type = varchar("type", 40)
    val userId = long("userId").nullable()
    val phone = varchar("phone", 11).nullable()
    val sessionId = long("sessionId").nullable()
    val ip = varchar("ip", 45).nullable()
    val traceId = varchar("traceId", 64).nullable()
    val detail = jsonb<JsonObject>("detail", Json)
}
