package com.kiva.application.usecase.campaign.usecase.query

import com.kiva.application.dto.CampaignDto
import com.kiva.application.usecase.campaign.CampaignShowcases
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.campaign.model.policy.CampaignShowcasePolicy
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import java.time.Clock

/** One campaign as the panel's form shows it: the answer of making and replacing one (AdminCampaign). */
class GetAdminCampaignUc(
    private val transactionManager: TransactionManager,
    private val campaignRepository: CampaignRepository,
    private val campaignShowcases: CampaignShowcases,
    private val clock: Clock,
) : QueryUseCase<GetAdminCampaignUc.Qry, CampaignDto, GetAdminCampaignUc.Failure> {

    private val logger = getLogger()

    data class Qry(val campaignId: CampaignId)

    override suspend fun execute(query: Qry): Result<CampaignDto, Failure> {
        logger.info("GetAdminCampaignUc.execute campaignId=${query.campaignId.value}")
        return transactionManager.inTransaction {
            val campaign = campaignRepository.find(query.campaignId) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(campaignShowcases.of(campaign, CampaignShowcasePolicy.LIMIT_FOR_ACTIVE, clock.instant()))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
