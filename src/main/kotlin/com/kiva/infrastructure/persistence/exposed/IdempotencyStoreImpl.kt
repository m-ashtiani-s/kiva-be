package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.IdempotencyStore
import com.kiva.application.port.IdempotentRequest
import com.kiva.application.port.RequestHash
import com.kiva.common.valueobject.UserId
import com.kiva.infrastructure.persistence.exposed.tbl.IdempotencyKeyTbl
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class IdempotencyStoreImpl : IdempotencyStore {

    /**
     * A transaction-level advisory lock on (customer, scope, key), released by the commit or the rollback, so it can't
     * be left behind; it also works for a key that has no row yet, which is exactly the case it is for. The lock's
     * number is a 64-bit hash: two keys sharing one only wait for each other, the row below is still matched exactly.
     */
    override fun lock(request: IdempotentRequest): IdempotencyStore.Earlier {
        TransactionManager.current().exec(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            listOf(TextColumnType() to "idempotency:${request.userId.value}:${request.scope}:${request.key.value}"),
        ) { }
        val row = IdempotencyKeyTbl.selectAll()
            .where { (IdempotencyKeyTbl.userId eq request.userId.value) and (IdempotencyKeyTbl.scope eq request.scope) and (IdempotencyKeyTbl.key eq request.key.value) }
            .singleOrNull()
            ?: return IdempotencyStore.Earlier.Unused
        return if (RequestHash.constructFromPersisted(row[IdempotencyKeyTbl.requestHash]) == request.requestHash) {
            IdempotencyStore.Earlier.SameRequest(row[IdempotencyKeyTbl.outcome])
        } else {
            IdempotencyStore.Earlier.OtherRequest
        }
    }

    override fun deleteAllOf(userId: UserId): Int = IdempotencyKeyTbl.deleteWhere { IdempotencyKeyTbl.userId eq userId.value }

    override fun deleteRecordedBefore(before: Instant): Int = IdempotencyKeyTbl.deleteWhere { createdOn less before.atOffset(ZoneOffset.UTC) }

    override fun record(request: IdempotentRequest, outcome: Map<String, String>, now: Instant) {
        IdempotencyKeyTbl.insert {
            it[userId] = request.userId.value
            it[scope] = request.scope
            it[key] = request.key.value
            it[requestHash] = request.requestHash.toByteArray()
            it[IdempotencyKeyTbl.outcome] = outcome
            it[createdOn] = now.atOffset(ZoneOffset.UTC)
        }
    }
}
