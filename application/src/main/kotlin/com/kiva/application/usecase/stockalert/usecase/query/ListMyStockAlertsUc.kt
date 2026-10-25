package com.kiva.application.usecase.stockalert.usecase.query

import com.kiva.application.dto.StockAlertDto
import com.kiva.application.usecase.stockalert.StockAlerts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import java.time.Clock
import java.time.Duration

/**
 * «اشتراک‌های من» (`GET /me/stock-alerts`; decision 7): the ACTIVE ones and those told in the last 90 days, newest made
 * first, at most 100; called-off ones are left out.
 */
class ListMyStockAlertsUc(
    private val transactionManager: TransactionManager,
    private val stockAlertRepository: StockAlertRepository,
    private val stockAlerts: StockAlerts,
    private val clock: Clock,
) : QueryUseCase<ListMyStockAlertsUc.Qry, List<StockAlertDto>, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token. */
    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<List<StockAlertDto>, Nothing> {
        logger.info("ListMyStockAlertsUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction {
            val since = clock.instant().minus(Duration.ofDays(StockAlertPolicy.KEEP_DAYS))
            Result.success(stockAlerts.of(stockAlertRepository.listMine(query.userId, since, StockAlertPolicy.MINE_LIMIT)))
        }
    }
}
