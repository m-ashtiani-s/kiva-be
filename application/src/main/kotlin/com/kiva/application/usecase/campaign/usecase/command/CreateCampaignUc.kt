package com.kiva.application.usecase.campaign.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.campaign.CampaignFields
import com.kiva.application.usecase.campaign.CampaignProducts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.campaign.CampaignDomainService
import com.kiva.domain.aggregate.campaign.entity.Campaign
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.repository.CampaignOverlapException
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import java.time.Clock

/**
 * The panel makes a campaign (`POST /admin/campaigns`; ADMIN, CONTENT_EDITOR; roadmap section 16.6). The form first,
 * every problem at once (400); then, under the campaigns' lock: its products there and not archived (400 on their index),
 * its slug nobody else's (409 DUPLICATE_SLUG), and when active no other active campaign overlapping (409
 * CAMPAIGN_OVERLAP). One CAMPAIGN_CREATED row. No price changes (decision 1).
 */
class CreateCampaignUc(
    private val transactionManager: TransactionManager,
    private val campaignRepository: CampaignRepository,
    private val campaignDomainService: CampaignDomainService,
    private val campaignProducts: CampaignProducts,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateCampaignUc.Cmd, CampaignId, CreateCampaignUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: CampaignFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<CampaignId, Failure> {
        logger.info("CreateCampaignUc.execute slug=${command.fields.slug} by=${command.actor.userId.value}")
        val draft = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            campaignRepository.lockAll()
            campaignProducts.problemOf(draft)?.let { return@inTransaction Result.failure(Failure.InvalidInput(listOf(it))) }
            campaignDomainService.checkSave(draft, self = null).unwrapOr { return@inTransaction Result.failure(Failure.Refused(it)) }

            val now = clock.instant()
            val campaign = Campaign.create(campaignRepository.nextId(), draft, now)
            try {
                campaignRepository.add(campaign)
            } catch (e: UniqueTakenException) {
                logger.info("CreateCampaignUc: ${e.message} between the check and the insert")
                return@inTransaction Result.failure(Failure.Refused(CampaignDomainService.Failure.DuplicateSlug))
            } catch (e: CampaignOverlapException) {
                logger.info("CreateCampaignUc: ${e.message} between the check and the insert")
                return@inTransaction Result.failure(Failure.Refused(CampaignDomainService.Failure.Overlap(null)))
            }
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.CAMPAIGN_CREATED, AuditEntityType.CAMPAIGN, campaign.id.value.toString(), campaign.title, null))
            logger.info("CreateCampaignUc: campaign ${campaign.id.value} made")
            Result.success(campaign.id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** The domain's 409: DUPLICATE_SLUG or CAMPAIGN_OVERLAP, passed on as it is. */
        data class Refused(val reason: CampaignDomainService.Failure) : Failure(reason.code, reason.kind, reason.field, meta = reason.meta)
    }
}
