package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductQmRepository.NamedInSearch
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.catalog.ColorFields
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import java.time.Clock

/**
 * The panel edits a palette color (`PUT /admin/colors/{colorKey}`; ADMIN, CATALOG_MANAGER). The key never changes:
 * stored events and category icons name it. So the body's `key` must be the path's, or 400 COLOR_KEY_IMMUTABLE on
 * `key`, checked once the color is found (404 NOT_FOUND, also for a malformed key). Without a `sortOrder` it keeps its
 * place; `isLight` is false unless sent, as on create.
 *
 * A new name is written into the products' search text in the same transaction. The same values again log nothing;
 * a change logs one COLOR_UPDATED row.
 */
class UpdateColorUc(
    private val transactionManager: TransactionManager,
    private val colorRepository: ColorRepository,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateColorUc.Cmd, ColorKey, UpdateColorUc.Failure> {

    private val logger = getLogger()

    /** @property colorKey the path's `{colorKey}` as it came. */
    data class Cmd(val colorKey: String, val fields: ColorFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<ColorKey, Failure> {
        logger.info("UpdateColorUc.execute color=${command.colorKey} by=${command.actor.userId.value}")
        val form = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val key = ColorKey.makeNew(command.colorKey).getOrNull() ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val before = colorRepository.findByKey(key) ?: return@inTransaction Result.failure(Failure.NotFound)
            if (form.key != key) return@inTransaction Result.failure(Failure.KeyImmutable)

            val after = before.change(form.name, form.hex, form.isLight, form.sortOrder ?: before.sortOrder)
            if (after.sameValuesAs(before)) return@inTransaction Result.success(key)

            colorRepository.update(after)
            if (after.name != before.name) {
                val refreshed = productQmRepository.refreshSearchText(NamedInSearch.Color(key))
                logger.info("UpdateColorUc: search text of $refreshed product(s) written again")
            }
            val changes = AuditDiff.build {
                field("name", before.name.value, after.name.value)
                field("hex", before.hex.value, after.hex.value)
                field("isLight", before.isLight, after.isLight)
                field("sortOrder", before.sortOrder, after.sortOrder)
            }
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.COLOR_UPDATED, AuditEntityType.COLOR, key.value, after.name.value, changes))
            Result.success(key)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, field, causes) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        /** The body names another color than the path. */
        data object KeyImmutable : Failure("COLOR_KEY_IMMUTABLE", FailureKind.INVALID, "key")
    }
}
