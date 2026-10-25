package com.kiva.application.usecase.campaign.usecase.query

import com.kiva.application.dto.CampaignDto
import com.kiva.application.usecase.campaign.CampaignShowcases
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.campaign.model.policy.CampaignShowcasePolicy
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import java.time.Clock

/**
 * The campaign running now (`GET /campaigns/active`, public; roadmap section 16.6): with its discounted products that
 * can be bought, at most 24, and the server's clock for the countdown; null (204) when none runs. Never cached.
 */
class GetActiveCampaignUc(
    private val transactionManager: TransactionManager,
    private val campaignRepository: CampaignRepository,
    private val campaignShowcases: CampaignShowcases,
    private val clock: Clock,
) : QueryUseCase<GetActiveCampaignUc.Qry, CampaignDto?, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<CampaignDto?, Nothing> {
        logger.info("GetActiveCampaignUc.execute")
        val now = clock.instant()
        return transactionManager.inTransaction {
            val live = campaignRepository.findLiveAt(now) ?: return@inTransaction Result.success(null)
            Result.success(campaignShowcases.of(live, CampaignShowcasePolicy.LIMIT_FOR_ACTIVE, now))
        }
    }
}
