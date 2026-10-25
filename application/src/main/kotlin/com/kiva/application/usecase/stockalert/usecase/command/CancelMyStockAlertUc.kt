package com.kiva.application.usecase.stockalert.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import java.time.Clock

/**
 * Calls a subscription off (`DELETE /me/stock-alerts/{alertId}`; decision 6): an ACTIVE one becomes CANCELLED (kept, not
 * deleted); the customer's own ended one is 204 unchanged. Someone else's, or none, is 404 STOCK_ALERT_NOT_FOUND. Under
 * the row's lock, so the job never tells one that was just called off.
 */
class CancelMyStockAlertUc(
    private val transactionManager: TransactionManager,
    private val stockAlertRepository: StockAlertRepository,
    private val clock: Clock,
) : CommandUseCase<CancelMyStockAlertUc.Cmd, Unit, CancelMyStockAlertUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [alertId] the path segment as it came. */
    data class Cmd(val userId: UserId, val alertId: String)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = StockAlertId.makeNew(command.alertId).getOrNull()
        logger.info("CancelMyStockAlertUc.execute userId=${command.userId.value} alertId=${id?.value ?: "(not an id)"}")
        if (id == null) return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            val alert = stockAlertRepository.lock(id)?.takeIf { it.userId == command.userId } ?: return@inTransaction Result.failure(Failure.NotFound)
            if (alert.cancel(clock.instant())) stockAlertRepository.save(alert)
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("STOCK_ALERT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
