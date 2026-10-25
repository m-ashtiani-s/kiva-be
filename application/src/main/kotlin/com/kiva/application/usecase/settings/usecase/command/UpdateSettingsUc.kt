package com.kiva.application.usecase.settings.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.settings.SettingsAudit
import com.kiva.application.usecase.settings.ShopSettingsFields
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * The panel saves the shop's settings (`PUT /admin/settings`; ADMIN): the whole set replaces the old one (roadmap
 * phase 4, section 9.6). The shape of every field first, all at once (400 with each field's path); then, in the
 * transaction, the rules between the fields (ShopSettings.change, 400 too); then the save, only over the version just
 * read: two saves at the same moment are 409 CHANGED_BY_SOMEONE_ELSE for the second, with nothing of it kept. A form
 * never tries again (section 4.5, rule 9): the panel reads the settings again.
 *
 * What a change means for what already happened is decision 5: every deadline already given stays where it is.
 * The same values again save and log nothing; a change logs one SETTINGS_UPDATED row with every field that changed.
 */
class UpdateSettingsUc(
    private val transactionManager: TransactionManager,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateSettingsUc.Cmd, Unit, UpdateSettingsUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: ShopSettingsFields, val actor: PanelActor) {
        override fun toString(): String = "Cmd(actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("UpdateSettingsUc.execute by=${command.actor.userId.value}")
        val change = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val before = shopSettingsRepository.get()
            val after = before.change(change).unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            if (after.sameValuesAs(before)) {
                logger.info("UpdateSettingsUc: nothing changed (version ${before.version})")
                return@inTransaction Result.success(Unit)
            }

            shopSettingsRepository.save(after)
            val changes = SettingsAudit.changes(before, after)
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.SETTINGS_UPDATED, AuditEntityType.SETTINGS, ENTITY_ID, null, changes))
            logger.info("UpdateSettingsUc: version ${before.version} saved as ${before.version + 1}, ${changes.size} field(s) changed")
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        /** The change log's id of the one set (contract: `shop`). */
        const val ENTITY_ID = "shop"
    }
}
