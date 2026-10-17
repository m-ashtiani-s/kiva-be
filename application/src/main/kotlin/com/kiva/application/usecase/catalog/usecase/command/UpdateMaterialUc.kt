package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductQmRepository.NamedInSearch
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
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
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import java.time.Clock

/**
 * The panel edits a material (`PUT /admin/materials/{materialId}`; ADMIN, CATALOG_MANAGER): the whole form replaces the
 * old values. The form first (400); a malformed id is the same 404 as one nobody has; the slug must be nobody else's
 * (409 DUPLICATE_SLUG). The slug may change (the read model follows it). A new name or slug is written into the search
 * text of the products made of it in the same transaction; its description never is (long text crowds the results).
 *
 * The same values again log nothing; a change logs one MATERIAL_UPDATED row.
 */
class UpdateMaterialUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateMaterialUc.Cmd, MaterialId, UpdateMaterialUc.Failure> {

    private val logger = getLogger()

    /** @property materialId the path's `{materialId}` as it came. */
    data class Cmd(val materialId: String, val fields: MaterialFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<MaterialId, Failure> {
        logger.info("UpdateMaterialUc.execute material=${command.materialId} slug=${command.fields.slug} by=${command.actor.userId.value}")
        val form = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val id = command.materialId.toLongOrNull()?.let { MaterialId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val before = materialRepository.findById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val holder = materialRepository.findBySlug(form.slug)
            if (holder != null && holder.id != id) return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))

            val after = before.change(form.slug, form.name, form.family, form.description, form.careInstructions)
            if (after.sameValuesAs(before)) return@inTransaction Result.success(id)

            try {
                materialRepository.update(after)
            } catch (e: UniqueTakenException) {
                logger.info("UpdateMaterialUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))
            }
            if (after.name != before.name || after.slug != before.slug) {
                val refreshed = productQmRepository.refreshSearchText(NamedInSearch.Material(after.slug))
                logger.info("UpdateMaterialUc: search text of $refreshed product(s) written again")
            }
            val changes = AuditDiff.build {
                field("name", before.name.value, after.name.value)
                field("slug", before.slug.value, after.slug.value)
                field("family", before.family, after.family)
                longText("description", before.description?.value, after.description?.value)
                list("careInstructions", before.careInstructions.items, after.careInstructions.items)
            }
            auditTrail.record(
                AuditEntry(clock.instant(), command.actor, AuditAction.MATERIAL_UPDATED, AuditEntityType.MATERIAL, id.value.toString(), after.name.value, changes),
            )
            Result.success(id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data class DuplicateSlug(val slug: Slug) : Failure("DUPLICATE_SLUG", FailureKind.CONFLICT, "slug", meta = mapOf("slug" to slug.value))
    }
}
