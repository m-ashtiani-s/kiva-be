package com.kiva.application.usecase.stockalert.usecase.query

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository

/**
 * The «موجود شد» job's candidates (roadmap section 15.3, decisions 9 and 13): ACTIVE subscriptions whose product is in
 * the shop and whose color (or one color, for «any color») can be bought now, whatever brought it back, the oldest
 * first, at most 200 a run. Only candidates: NotifyStockAlertUc checks each again under its lock.
 */
class FindDueStockAlertsUc(
    private val transactionManager: TransactionManager,
    private val stockAlertRepository: StockAlertRepository,
) : QueryUseCase<FindDueStockAlertsUc.Qry, List<StockAlertId>, Nothing> {

    private val logger = getLogger()

    data class Qry(val limit: Int = StockAlertPolicy.BATCH)

    override suspend fun execute(query: Qry): Result<List<StockAlertId>, Nothing> {
        logger.info("FindDueStockAlertsUc.execute limit=${query.limit}")
        return transactionManager.inTransaction { Result.success(stockAlertRepository.findDue(query.limit)) }
    }
}
