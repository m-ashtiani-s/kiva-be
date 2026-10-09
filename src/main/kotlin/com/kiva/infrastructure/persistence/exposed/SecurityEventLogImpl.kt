package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.slf4j.MDC
import java.time.ZoneOffset

/**
 * Inserts into securityevent_tbl, in the use case's transaction. The request's traceId (the same as in the log and
 * in error bodies) goes with every event, so an event can be followed back to its request.
 */
class SecurityEventLogImpl : SecurityEventLog {

    override fun record(event: SecurityEvent) {
        SecurityEventTbl.insert {
            it[occurredOn] = event.occurredOn.atOffset(ZoneOffset.UTC)
            it[type] = event.type.name
            it[userId] = event.userId?.value
            it[phone] = event.phone?.value
            it[ip] = event.ip
            it[traceId] = MDC.get("traceId")
            it[detail] = JsonObject(event.detail.mapValues { (_, value) -> value.toJson() })
        }
    }

    private fun Any.toJson(): JsonElement = when (this) {
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
