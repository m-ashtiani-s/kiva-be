package com.kiva.application.port

import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.valueobject.UserId
import java.time.Duration
import java.time.Instant

/**
 * Writes one row of the panel's change log, in the CALLER's transaction: it is rolled back with the work it records
 * (roadmap section 5.1). Every panel write calls it once, at the end of its transaction and only when something
 * changed; reads and the system's jobs never do.
 */
interface AuditTrail {

    fun record(entry: AuditEntry)

    /**
     * Removes at most [limit] rows recorded before [before] (the nightly retention). The database refuses to remove a
     * row younger than [KEPT_FOR], so [before] must not be later than that. Returns how many went.
     */
    fun deleteRecordedBefore(before: Instant, limit: Int): Int

    companion object {
        /** How long a row is kept: the rolling window of the contract. */
        val KEPT_FOR: Duration = Duration.ofDays(90)
    }
}

/**
 * One row of the change log.
 *
 * @property entityId the thing's id or code as the panel shows it (`12`, `KV-218340`, `lilac`).
 * @property entityLabel the thing's name at the moment of the change, for reading the log later.
 * @property changes the fields that changed, or null for an action with no "from what to what" (a delete).
 */
data class AuditEntry(
    val at: Instant,
    val actor: PanelActor,
    val action: AuditAction,
    val entityType: AuditEntityType,
    val entityId: String,
    val entityLabel: String?,
    val changes: List<AuditChange>?,
)

/**
 * One field that changed. [from] and [to] are the values as text (AuditDiff), or both null when only the fact of the
 * change is kept (long text, a secret).
 */
data class AuditChange(val field: String, val from: String?, val to: String?)

/** Who did it, from where. Built by the route from the access token and the request; never from the body. */
data class PanelActor(val userId: UserId, val ip: String?)
