package com.kiva.infrastructure.persistence.exposed.tbl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The panel's change log: rows are only ever inserted, and only the retention job removes the ones past 90 days (a
 * trigger refuses anything else). Created by V21__audit_log.sql (Exposed never creates tables); keep both in step.
 */
object AuditLogTbl : LongIdTable(name = "auditlog_tbl", columnName = "pId") {
    val at = timestampWithTimeZone("at")
    val actorId = long("actorId")
    val action = varchar("action", 60)
    val entityType = varchar("entityType", 30)
    val entityId = varchar("entityId", 64)
    val entityLabel = varchar("entityLabel", 200).nullable()

    /** `[{field, from, to}]`, or null for an action with no "from what to what". */
    val changes = jsonb<JsonArray>("changes", Json).nullable()
    val ip = varchar("ip", 45).nullable()
    val traceId = varchar("traceId", 64).nullable()
}
