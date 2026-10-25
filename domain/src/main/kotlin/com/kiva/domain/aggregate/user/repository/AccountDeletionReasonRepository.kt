package com.kiva.domain.aggregate.user.repository

import com.kiva.domain.aggregate.user.model.valueobject.DeletionReason
import java.time.Instant

/**
 * The reasons customers gave for leaving (roadmap phase 14, decision 11): text and time only, never who. Written in
 * the transaction that deletes the account; nothing reads them through the app yet (an SQL query does).
 */
interface AccountDeletionReasonRepository {
    fun add(reason: DeletionReason, now: Instant)
}
