package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.catalog.ColorFields
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import java.time.Clock

/**
 * A new color of the palette (`POST /admin/colors`; ADMIN, CATALOG_MANAGER). The form first (400, all at once; `hex`
 * is stored uppercased, `isLight` is false unless sent); then the key must be free (409 DUPLICATE_CODE on `key`, the
 * same answer when another request takes it at the same moment). Without a `sortOrder` it goes after the last color.
 * Logs one COLOR_CREATED row.
 *
 * @return the new color's key.
 */
class CreateColorUc(
    private val transactionManager: TransactionManager,
    private val colorRepository: ColorRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateColorUc.Cmd, ColorKey, CreateColorUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: ColorFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<ColorKey, Failure> {
        logger.info("CreateColorUc.execute key=${command.fields.key} by=${command.actor.userId.value}")
        val form = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            if (colorRepository.findByKey(form.key) != null) return@inTransaction Result.failure(Failure.DuplicateKey(form.key))
            val sortOrder = form.sortOrder ?: ((colorRepository.maxSortOrder() ?: 0) + 1)
            val color = Color.create(form.key, form.name, form.hex, form.isLight, sortOrder)
            try {
                colorRepository.insert(color)
            } catch (e: UniqueTakenException) {
                logger.info("CreateColorUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateKey(form.key))
            }
            auditTrail.record(
                AuditEntry(clock.instant(), command.actor, AuditAction.COLOR_CREATED, AuditEntityType.COLOR, color.key.value, color.name.value, null),
            )
            Result.success(color.key)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data class DuplicateKey(val key: ColorKey) : Failure("DUPLICATE_CODE", FailureKind.CONFLICT, "key", meta = mapOf("key" to key.value))
    }
}
