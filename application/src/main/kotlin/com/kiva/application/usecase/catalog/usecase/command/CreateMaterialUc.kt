package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.catalog.MaterialFields
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialIdGenerator
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import java.time.Clock

/**
 * A new material (`POST /admin/materials`; ADMIN, CATALOG_MANAGER). The form first (400, all at once); then the slug
 * must be free (409 DUPLICATE_SLUG, the same answer when another request takes it at the same moment). Logs one
 * MATERIAL_CREATED row.
 *
 * @return the new material's id.
 */
class CreateMaterialUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
    private val materialIdGenerator: MaterialIdGenerator,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateMaterialUc.Cmd, MaterialId, CreateMaterialUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: MaterialFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<MaterialId, Failure> {
        logger.info("CreateMaterialUc.execute slug=${command.fields.slug} by=${command.actor.userId.value}")
        val form = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            if (materialRepository.findBySlug(form.slug) != null) return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))
            val material = Material.create(materialIdGenerator.nextMaterialId(), form.slug, form.name, form.family, form.description, form.careInstructions)
            try {
                materialRepository.insert(material)
            } catch (e: UniqueTakenException) {
                logger.info("CreateMaterialUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))
            }
            auditTrail.record(
                AuditEntry(clock.instant(), command.actor, AuditAction.MATERIAL_CREATED, AuditEntityType.MATERIAL, material.id.value.toString(), material.name.value, null),
            )
            Result.success(material.id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data class DuplicateSlug(val slug: Slug) : Failure("DUPLICATE_SLUG", FailureKind.CONFLICT, "slug", meta = mapOf("slug" to slug.value))
    }
}
