package com.kiva.application.usecase.stockalert.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import java.time.Clock
import java.time.Duration

/**
 * The nightly clean-up `delete-old-stock-alerts` (decision 8): subscriptions told or called off more than 90 days ago
 * are deleted; ACTIVE ones stay however old.
 */
class DeleteOldStockAlertsUc(
    private val transactionManager: TransactionManager,
    private val stockAlertRepository: StockAlertRepository,
    private val clock: Clock,
) : CommandUseCase<DeleteOldStockAlertsUc.Cmd, Int, Nothing> {

    private val logger = getLogger()

    data object Cmd

    override suspend fun execute(command: Cmd): Result<Int, Nothing> {
        val deleted = transactionManager.inTransaction {
            Result.success(stockAlertRepository.deleteEndedBefore(clock.instant().minus(Duration.ofDays(StockAlertPolicy.KEEP_DAYS))))
        }.getOrNull() ?: 0
        logger.info("DeleteOldStockAlertsUc: $deleted ended stock alerts deleted")
        return Result.success(deleted)
    }
}
