package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.user.model.valueobject.DeletionReason
import com.kiva.domain.aggregate.user.repository.AccountDeletionReasonRepository
import com.kiva.infrastructure.persistence.exposed.tbl.AccountDeletionReasonTbl
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class AccountDeletionReasonRepositoryImpl : AccountDeletionReasonRepository {
    override fun add(reason: DeletionReason, now: Instant) {
        AccountDeletionReasonTbl.insert {
            it[AccountDeletionReasonTbl.reason] = reason.value
            it[createdOn] = now.atOffset(ZoneOffset.UTC)
        }
    }
}
