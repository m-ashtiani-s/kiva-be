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
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import java.time.Clock

/**
 * The panel removes a category (`DELETE /admin/categories/{categoryId}`; ADMIN, CATALOG_MANAGER), only while no product
 * of any status (drafts and archived ones too) is in it: otherwise 409 IN_USE with `meta.productCount`. The count comes
 * from the read model; the read model's foreign key is the second wall for a product saved in between, which gets the
 * same 409. Discount codes that still name the category don't stop it: an old id there is harmless.
 *
 * A malformed id is the same 404 as one nobody has. A removal logs one CATEGORY_DELETED row.
 */
class DeleteCategoryUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteCategoryUc.Cmd, Unit, DeleteCategoryUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val categoryId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteCategoryUc.execute category=${command.categoryId} by=${command.actor.userId.value}")
        val id = command.categoryId.toLongOrNull()?.let { CategoryId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val category = categoryRepository.findById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val products = productQmRepository.countAllByCategory()[category.slug] ?: 0
            if (products > 0) return@inTransaction Result.failure(Failure.InUse(products))
            try {
                categoryRepository.delete(id)
            } catch (e: StillReferencedException) {
                logger.info("DeleteCategoryUc: ${e.message}; a product got it after the count")
                return@inTransaction Result.failure(Failure.InUse(1))
            }
            auditTrail.record(
                AuditEntry(clock.instant(), command.actor, AuditAction.CATEGORY_DELETED, AuditEntityType.CATEGORY, id.value.toString(), category.name.value, null),
            )
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        /** [productCount] products of any status are in it (at least 1 when the foreign key refused the removal). */
        data class InUse(val productCount: Int) : Failure("IN_USE", FailureKind.CONFLICT, mapOf("productCount" to productCount))
    }
}
