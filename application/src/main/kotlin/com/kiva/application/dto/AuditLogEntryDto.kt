package com.kiva.application.dto

import com.kiva.application.port.AuditChange
import com.kiva.application.repository.AuditLogEntryQm
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import java.time.Instant

/**
 * One row of the panel's change log (the contract's AuditLogEntry, its `summary` aside: the HTTP layer writes that
 * sentence from [action], [entityLabel] and [changes]).
 *
 * @property actorPhone the actor's phone as it is now; null only for an account that is no longer there, which never
 *   happens in the shop (accounts are made anonymous, not removed).
 */
data class AuditLogEntryDto(
    val id: Long,
    val at: Instant,
    val actorId: Long,
    val actorFullName: String?,
    val actorPhone: String?,
    val action: AuditAction,
    val entityType: AuditEntityType,
    val entityId: String,
    val entityLabel: String?,
    val changes: List<AuditChange>?,
    val ip: String?,
) {
    override fun toString() = "AuditLogEntryDto(id=$id, action=$action)"

    companion object {
        fun from(row: AuditLogEntryQm) = AuditLogEntryDto(
            id = row.id,
            at = row.at,
            actorId = row.actorId,
            actorFullName = row.actorFullName,
            actorPhone = row.actorPhone,
            action = row.action,
            entityType = row.entityType,
            entityId = row.entityId,
            entityLabel = row.entityLabel,
            changes = row.changes,
            ip = row.ip,
        )
    }
}
