package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.AuditChange
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.MDC
import java.time.Instant
import java.time.ZoneOffset

/**
 * Inserts into auditlog_tbl, in the use case's transaction, so a row is rolled back with the work it records. The
 * request's traceId (the same as in the app's log and in error bodies) goes with every row; the use case never knows
 * about it.
 */
class AuditTrailImpl : AuditTrail {

    override fun record(entry: AuditEntry) {
        AuditLogTbl.insert {
            it[at] = entry.at.atOffset(ZoneOffset.UTC)
            it[actorId] = entry.actor.userId.value
            it[action] = entry.action.name
            it[entityType] = entry.entityType.name
            it[entityId] = entry.entityId
            it[entityLabel] = entry.entityLabel?.take(MAX_LABEL)
            it[changes] = entry.changes?.let(::jsonOf)
            it[ip] = entry.actor.ip
            it[traceId] = MDC.get("traceId")
        }
    }

    override fun deleteRecordedBefore(before: Instant, limit: Int): Int {
        val oldest = AuditLogTbl.select(AuditLogTbl.id)
            .where { AuditLogTbl.at less before.atOffset(ZoneOffset.UTC) }
            .orderBy(AuditLogTbl.at to SortOrder.ASC)
            .limit(limit)
        return AuditLogTbl.deleteWhere { AuditLogTbl.id inSubQuery oldest }
    }

    companion object {
        private const val MAX_LABEL = 200

        fun jsonOf(changes: List<AuditChange>): JsonArray = JsonArray(
            changes.map { change ->
                JsonObject(
                    mapOf(
                        "field" to JsonPrimitive(change.field),
                        "from" to (change.from?.let(::JsonPrimitive) ?: JsonNull),
                        "to" to (change.to?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                )
            },
        )

        fun changesOf(json: JsonArray): List<AuditChange> = json.map { item ->
            val change = item.jsonObject
            AuditChange(
                field = change.getValue("field").jsonPrimitive.content,
                from = change["from"]?.jsonPrimitive?.contentOrNull,
                to = change["to"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}
