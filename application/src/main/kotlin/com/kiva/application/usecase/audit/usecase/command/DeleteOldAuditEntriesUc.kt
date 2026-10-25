package com.kiva.application.usecase.audit.usecase.command

import com.kiva.application.port.AuditTrail
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import java.time.Clock
import java.time.Duration

/**
 * Removes the change log's rows older than 90 days (the nightly job `delete-old-audit-entries`, roadmap section 5.1):
 * the rolling window of the contract. Rows go in batches of [BATCH_SIZE], each in a transaction of its own, until none
 * is left, so one night with many rows never holds one long transaction. Running it again removes nothing more.
 *
 * The database refuses to remove a row younger than 90 days by ITS clock, so the cut is kept [SAFETY_MARGIN] earlier
 * than ours: a few seconds between the two clocks never fail a batch.
 */
class DeleteOldAuditEntriesUc(
    private val transactionManager: TransactionManager,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteOldAuditEntriesUc.Cmd, Int, Nothing> {

    private val logger = getLogger()

    data object Cmd

    /** @return how many rows went. */
    override suspend fun execute(command: Cmd): Result<Int, Nothing> {
        logger.info("DeleteOldAuditEntriesUc.execute")
        val before = clock.instant().minus(AuditTrail.KEPT_FOR).minus(SAFETY_MARGIN)
        var removed = 0
        while (true) {
            val batch = transactionManager.inTransaction { Result.success(auditTrail.deleteRecordedBefore(before, BATCH_SIZE)) }.getOrNull() ?: 0
            removed += batch
            if (batch < BATCH_SIZE) break
        }
        logger.info("DeleteOldAuditEntriesUc: $removed rows recorded before $before removed")
        return Result.success(removed)
    }

    companion object {
        const val BATCH_SIZE = 5000
        val SAFETY_MARGIN: Duration = Duration.ofHours(1)
    }
}
