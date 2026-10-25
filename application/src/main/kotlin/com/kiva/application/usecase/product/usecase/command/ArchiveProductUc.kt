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
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import java.time.Clock

/**
 * The panel removes a product (`DELETE /admin/products/{productId}`; ADMIN, CATALOG_MANAGER): it is archived, never
 * deleted, because past orders point to it. Already archived is done too (204), with no event and no change log row.
 * Past orders and open reservations are not touched; a cart holding it shows the line as not purchasable (the cart's
 * own rule). It leaves every wishlist in the same transaction (phase 9, decision 6): an archived product never comes
 * back, unlike a draft, whose rows stay hidden.
 */
class ArchiveProductUc(
    private val transactionManager: TransactionManager,
    private val productRepository: ProductRepository,
    private val wishlistRepository: WishlistRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<ArchiveProductUc.Cmd, Unit, ArchiveProductUc.Failure> {

    private val logger = getLogger()

    /** @property productId the path's `{productId}` as it came. */
    data class Cmd(val productId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("ArchiveProductUc.execute product=${command.productId} by=${command.actor.userId.value}")
        val id = command.productId.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val product = productRepository.get(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            if (product.status == ProductStatus.ARCHIVED) return@inTransaction Result.success(Unit)

            val before = product.status
            val now = clock.instant()
            product.archive(command.actor.userId, now).getOrNull() ?: error("Not archived yet")
            productRepository.save(product)
            wishlistRepository.deleteProduct(id)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.PRODUCT_ARCHIVED, AuditEntityType.PRODUCT, id.value.toString(), product.details.name.value,
                    AuditDiff.build { field("status", before, product.status) },
                ),
            )
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
