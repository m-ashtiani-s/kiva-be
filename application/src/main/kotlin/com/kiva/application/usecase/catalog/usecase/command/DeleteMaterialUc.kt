package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.repository.StillReferencedException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import java.time.Clock

/**
 * The panel removes a material (`DELETE /admin/materials/{materialId}`; ADMIN, CATALOG_MANAGER), only while no product
 * of any status is made of it: otherwise 409 IN_USE with `meta.productCount` (the read model's foreign key is the
 * second wall). A malformed id is the same 404 as one nobody has. A removal logs one MATERIAL_DELETED row.
 */
class DeleteMaterialUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteMaterialUc.Cmd, Unit, DeleteMaterialUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val materialId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteMaterialUc.execute material=${command.materialId} by=${command.actor.userId.value}")
        val id = command.materialId.toLongOrNull()?.let { MaterialId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val material = materialRepository.findById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val products = productQmRepository.countAllByMaterial()[material.slug] ?: 0
            if (products > 0) return@inTransaction Result.failure(Failure.InUse(products))
            try {
                materialRepository.delete(id)
            } catch (e: StillReferencedException) {
                logger.info("DeleteMaterialUc: ${e.message}; a product got it after the count")
                return@inTransaction Result.failure(Failure.InUse(1))
            }
            auditTrail.record(
                AuditEntry(clock.instant(), command.actor, AuditAction.MATERIAL_DELETED, AuditEntityType.MATERIAL, id.value.toString(), material.name.value, null),
            )
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data class InUse(val productCount: Int) : Failure("IN_USE", FailureKind.CONFLICT, mapOf("productCount" to productCount))
    }
}
