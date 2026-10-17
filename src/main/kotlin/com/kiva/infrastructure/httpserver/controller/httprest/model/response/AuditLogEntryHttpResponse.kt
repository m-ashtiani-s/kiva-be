package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AuditLogEntryDto
import kotlinx.serialization.Serializable

/** The contract's AuditLogEntry: one row of the panel's change log, its sentence written by AuditTexts. */
@Serializable
data class AuditLogEntryHttpResponse(
    val id: Long,
    val at: String,
    val actor: Actor,
    val action: String,
    val entityType: String,
    val entityId: String,
    val entityLabel: String?,
    val summary: String,
    val changes: List<Change>?,
    val ip: String?,
) {
    /** The actor as their account is now; the phone whole, since only an ADMIN reads the log. */
    @Serializable
    data class Actor(val id: Long, val fullName: String?, val phone: String?)

    @Serializable
    data class Change(val field: String, val from: String?, val to: String?)

    companion object {
        fun from(entry: AuditLogEntryDto) = AuditLogEntryHttpResponse(
            id = entry.id,
            at = entry.at.toString(),
            actor = Actor(entry.actorId, entry.actorFullName, entry.actorPhone),
            action = entry.action.name,
            entityType = entry.entityType.name,
            entityId = entry.entityId,
            entityLabel = entry.entityLabel,
            summary = AuditTexts.summary(entry),
            changes = entry.changes?.map { Change(it.field, it.from, it.to) },
            ip = entry.ip,
        )
    }
}
