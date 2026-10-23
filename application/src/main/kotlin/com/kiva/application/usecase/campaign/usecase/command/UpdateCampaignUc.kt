package com.kiva.application.usecase.campaign.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.campaign.CampaignFields
import com.kiva.application.usecase.campaign.CampaignProducts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.campaign.CampaignDomainService
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.repository.CampaignOverlapException
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import java.time.Clock

/**
 * The panel replaces a campaign (`PUT /admin/campaigns/{campaignId}`; ADMIN, CONTENT_EDITOR; roadmap section 16.6): the
 * whole form, a field left out its default. The form first (400); then, under the campaigns' lock, the campaign (404
 * NOT_FOUND, also for an id that can't be one), its products (400), its slug (409 DUPLICATE_SLUG) and the overlap (409
 * CAMPAIGN_OVERLAP), its own row left out of both. A save that changes nothing writes nothing; otherwise one
 * CAMPAIGN_UPDATED row with what changed.
 */
class UpdateCampaignUc(
    private val transactionManager: TransactionManager,
    private val campaignRepository: CampaignRepository,
    private val campaignDomainService: CampaignDomainService,
    private val campaignProducts: CampaignProducts,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateCampaignUc.Cmd, CampaignId, UpdateCampaignUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val campaignId: String, val fields: CampaignFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<CampaignId, Failure> {
        logger.info("UpdateCampaignUc.execute campaignId=${command.campaignId} by=${command.actor.userId.value}")
        val draft = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val id = command.campaignId.trim().normalizeDigits().toLongOrNull()?.let { CampaignId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            campaignRepository.lockAll()
            val before = campaignRepository.find(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            campaignProducts.problemOf(draft)?.let { return@inTransaction Result.failure(Failure.InvalidInput(listOf(it))) }
            campaignDomainService.checkSave(draft, self = id).unwrapOr { return@inTransaction Result.failure(Failure.Refused(it)) }

            val now = clock.instant()
            val after = before.replacedBy(draft, now) ?: return@inTransaction Result.success(id)
            try {
                campaignRepository.save(after)
            } catch (e: UniqueTakenException) {
                logger.info("UpdateCampaignUc: ${e.message} between the check and the update")
                return@inTransaction Result.failure(Failure.Refused(CampaignDomainService.Failure.DuplicateSlug))
            } catch (e: CampaignOverlapException) {
                logger.info("UpdateCampaignUc: ${e.message} between the check and the update")
                return@inTransaction Result.failure(Failure.Refused(CampaignDomainService.Failure.Overlap(null)))
            }
            val changes = AuditDiff.build {
                field("slug", before.slug.value, after.slug.value)
                field("title", before.title, after.title)
                field("subtitle", before.subtitle, after.subtitle)
                field("icon", before.icon?.value, after.icon?.value)
                field("startsAt", before.window.startsAt, after.window.startsAt)
                field("endsAt", before.window.endsAt, after.window.endsAt)
                list("productIds", before.productIds.map { it.value }, after.productIds.map { it.value })
                field("isActive", before.isActive, after.isActive)
            }
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.CAMPAIGN_UPDATED, AuditEntityType.CAMPAIGN, id.value.toString(), after.title, changes))
            Result.success(id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data class Refused(val reason: CampaignDomainService.Failure) : Failure(reason.code, reason.kind, reason.field, meta = reason.meta)
    }
}
