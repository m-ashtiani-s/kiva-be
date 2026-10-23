package com.kiva.application.usecase.campaign.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import java.time.Clock

/**
 * The panel deletes a campaign for good (`DELETE /admin/campaigns/{campaignId}`; ADMIN, CONTENT_EDITOR; decision 18).
 * Nothing else changes: no price, no order (decision 1). One CAMPAIGN_DELETED row; one nobody has is 404 NOT_FOUND.
 */
class DeleteCampaignUc(
    private val transactionManager: TransactionManager,
    private val campaignRepository: CampaignRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteCampaignUc.Cmd, Unit, DeleteCampaignUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val campaignId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteCampaignUc.execute campaignId=${command.campaignId} by=${command.actor.userId.value}")
        val id = command.campaignId.trim().normalizeDigits().toLongOrNull()?.let { CampaignId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            campaignRepository.lockAll()
            val campaign = campaignRepository.find(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            campaignRepository.delete(id)
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.CAMPAIGN_DELETED, AuditEntityType.CAMPAIGN, id.value.toString(), campaign.title, null))
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
