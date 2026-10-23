package com.kiva.application.usecase.content.usecase.query

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.settings.model.valueobject.SiteStats
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * The shop's public numbers (`GET /site/stats`, public; roadmap section 16.2, decision 14): exactly what the panel typed
 * in the settings (`siteStats`). Nothing is counted from orders or reviews. Never cached.
 */
class GetSiteStatsUc(
    private val transactionManager: TransactionManager,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<GetSiteStatsUc.Qry, SiteStats, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<SiteStats, Nothing> {
        logger.info("GetSiteStatsUc.execute")
        return transactionManager.inTransaction { Result.success(shopSettingsRepository.get().siteStats) }
    }
}
