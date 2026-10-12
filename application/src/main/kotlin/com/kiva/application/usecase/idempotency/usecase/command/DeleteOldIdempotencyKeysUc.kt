package com.kiva.application.usecase.idempotency.usecase.command

import com.kiva.application.port.IdempotencyStore
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import java.time.Clock

/**
 * Forgets the `Idempotency-Key`s older than the contract keeps them (24 hours, IdempotencyStore.KEPT_FOR): the hourly
 * clean-up of roadmap section 15. A request repeated after that is a new one.
 */
class DeleteOldIdempotencyKeysUc(
    private val transactionManager: TransactionManager,
    private val idempotencyStore: IdempotencyStore,
    private val clock: Clock,
) : CommandUseCase<DeleteOldIdempotencyKeysUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    data object Cmd

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("DeleteOldIdempotencyKeysUc.execute")
        val before = clock.instant().minus(IdempotencyStore.KEPT_FOR)
        return transactionManager.inTransaction {
            val removed = idempotencyStore.deleteRecordedBefore(before)
            logger.info("DeleteOldIdempotencyKeysUc: $removed keys recorded before $before removed")
            Result.success(Unit)
        }
    }
}
