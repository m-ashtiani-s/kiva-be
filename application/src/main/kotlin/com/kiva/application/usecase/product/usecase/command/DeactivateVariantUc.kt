package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock

/**
 * The panel takes a color out of the shop (`DELETE /admin/products/{productId}/variants/{variantId}`; ADMIN,
 * CATALOG_MANAGER). It stays on the product, because past orders point to it; if it was the default, the next active
 * color (position, then id) becomes the default. Already inactive is done too (204), with no event and no row. The
 * last active color of a published product can't go (422 LAST_ACTIVE_VARIANT: unpublish the product first); an
 * archived product can't change (422 PRODUCT_ARCHIVED). A color of another product is 404 VARIANT_NOT_FOUND.
 */
class DeactivateVariantUc(
    private val transactionManager: TransactionManager,
    private val productRepository: ProductRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeactivateVariantUc.Cmd, Unit, DeactivateVariantUc.Failure> {

    private val logger = getLogger()

    /** @property productId / [variantId] the path's parameters as they came. */
    data class Cmd(val productId: String, val variantId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeactivateVariantUc.execute product=${command.productId} variant=${command.variantId} by=${command.actor.userId.value}")
        val productId = command.productId.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.ProductNotFound)
        val variantId = command.variantId.toLongOrNull()?.let { VariantId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.VariantNotFound)

        return transactionManager.inTransaction {
            val product = productRepository.get(productId) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val variant = product.findVariant(variantId) ?: return@inTransaction Result.failure(Failure.VariantNotFound)
            val now = clock.instant()
            when (product.deactivateVariant(variantId, command.actor.userId, now).failureOrNull()) {
                null -> Unit
                Product.DeactivateVariantFailure.AlreadyInactive -> return@inTransaction Result.success(Unit)
                Product.DeactivateVariantFailure.LastActiveVariant -> return@inTransaction Result.failure(Failure.LastActiveVariant)
                Product.DeactivateVariantFailure.ProductIsArchived -> return@inTransaction Result.failure(Failure.ProductArchived)
                is Product.DeactivateVariantFailure.VariantNotFound -> error("Found just above")
            }
            productRepository.save(product)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.VARIANT_DEACTIVATED, AuditEntityType.VARIANT, variantId.value.toString(),
                    variantLabel(product.details.name.value, variant.colorKey), AuditDiff.build { field("isActive", true, false) },
                ),
            )
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object VariantNotFound : Failure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object ProductArchived : Failure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data object LastActiveVariant : Failure("LAST_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE)
    }
}
