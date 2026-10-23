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
 * The panel's campaigns (`GET /admin/campaigns`; ADMIN, CONTENT_EDITOR): every one, the latest start first, each with
 * its products as the shop would show them now (even one not running), at most 24.
 */
class ListAdminCampaignsUc(
    private val transactionManager: TransactionManager,
    private val campaignRepository: CampaignRepository,
    private val campaignShowcases: CampaignShowcases,
    private val clock: Clock,
) : QueryUseCase<ListAdminCampaignsUc.Qry, List<CampaignDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<CampaignDto>, Nothing> {
        logger.info("ListAdminCampaignsUc.execute")
        return transactionManager.inTransaction {
            Result.success(campaignShowcases.of(campaignRepository.findAll(), CampaignShowcasePolicy.LIMIT_FOR_ACTIVE, clock.instant()))
        }
    }
}
