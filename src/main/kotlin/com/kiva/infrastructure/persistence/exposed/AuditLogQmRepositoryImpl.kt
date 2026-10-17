package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.AuditLogEntryQm
import com.kiva.application.repository.AuditLogFilter
import com.kiva.application.repository.AuditLogQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.ZoneOffset

/**
 * Reads auditlog_tbl for the panel, with each actor's name and phone from user_tbl as the account is now (a left join:
 * a row stays even if its account were gone). Runs inside the use case's transaction; it never opens one itself.
 */
class AuditLogQmRepositoryImpl : AuditLogQmRepository {

    override fun find(filter: AuditLogFilter, pageable: Pageable): Page<AuditLogEntryQm> =
        AuditLogTbl.join(UserTbl, JoinType.LEFT, AuditLogTbl.actorId, UserTbl.id)
            .selectAll()
            .where { conditionOf(filter) }
            .paginate(pageable, SORT_ORDERS, tiebreaker = AuditLogTbl.id) { it.toEntry() }

    private fun conditionOf(filter: AuditLogFilter): Op<Boolean> {
        val conditions = buildList<Op<Boolean>> {
            filter.entityType?.let { add(AuditLogTbl.entityType eq it.name) }
            filter.entityId?.let { add(AuditLogTbl.entityId eq it) }
            filter.actorId?.let { add(AuditLogTbl.actorId eq it) }
            filter.from?.let { add(AuditLogTbl.at greaterEq it.atOffset(ZoneOffset.UTC)) }
            filter.until?.let { add(AuditLogTbl.at less it.atOffset(ZoneOffset.UTC)) }
        }
        return conditions.reduceOrNull { all, next -> all and next } ?: Op.TRUE
    }

    private fun ResultRow.toEntry() = AuditLogEntryQm(
        id = this[AuditLogTbl.id].value,
        at = this[AuditLogTbl.at].toInstant(),
        actorId = this[AuditLogTbl.actorId],
        actorFullName = listOfNotNull(this.getOrNull(UserTbl.firstName), this.getOrNull(UserTbl.lastName)).joinToString(" ").ifEmpty { null },
        actorPhone = this.getOrNull(UserTbl.phone),
        action = AuditAction.valueOf(this[AuditLogTbl.action]),
        entityType = AuditEntityType.valueOf(this[AuditLogTbl.entityType]),
        entityId = this[AuditLogTbl.entityId],
        entityLabel = this[AuditLogTbl.entityLabel],
        changes = this[AuditLogTbl.changes]?.let(AuditTrailImpl::changesOf),
        ip = this[AuditLogTbl.ip],
    )

    private companion object {
        /** Newest first; the id breaks ties between rows of the same instant. */
        val SORT_ORDERS: SortOrders = mapOf(
            AuditLogQmRepository.NEWEST to listOf(AuditLogTbl.at to SortOrder.DESC, AuditLogTbl.id to SortOrder.DESC),
        )
    }
}
