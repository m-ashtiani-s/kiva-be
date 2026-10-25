package com.kiva.application.repository

import com.kiva.application.port.AuditChange
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import java.time.Instant

/** Reads the panel's change log (`GET /admin/audit-log`), newest first, with each actor as their account is now. */
interface AuditLogQmRepository {

    fun find(filter: AuditLogFilter, pageable: Pageable): Page<AuditLogEntryQm>

    companion object {
        /** The only order: newest first (`at`, then the id, both descending). */
        const val NEWEST = "newest"
    }
}

/**
 * What to look for; every given part must match.
 *
 * @property from the first instant kept (inclusive); [until] the first one past the range (exclusive).
 */
data class AuditLogFilter(
    val entityType: AuditEntityType? = null,
    val entityId: String? = null,
    val actorId: Long? = null,
    val from: Instant? = null,
    val until: Instant? = null,
)

/**
 * One row of the log, with its actor as their account is now: [actorFullName] null when they have no name (or the
 * account is gone), [actorPhone] their phone (null only when the account is gone).
 */
data class AuditLogEntryQm(
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
)
