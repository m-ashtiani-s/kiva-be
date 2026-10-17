package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.Variant
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock

/**
 * The panel saves a color's form (`PUT /admin/products/{productId}/variants/{variantId}`; ADMIN, CATALOG_MANAGER): its
 * own price (null = the product's), position, default, barcode, gallery and whether it is in the shop. Its stock is
 * not read here (it changes only by an adjustment), so `stockQuantity` is ignored.
 *
 * The form first (400). Then: the product (404 PRODUCT_NOT_FOUND) and the color on it (404 VARIANT_NOT_FOUND, a color of
 * another product too); the key and the code never change (400 COLOR_KEY_IMMUTABLE / SKU_IMMUTABLE, a code left out
 * meaning the same); not archived (422); a color asked to be the default and out of the shop at once is 422
 * INACTIVE_VARIANT_CANNOT_BE_DEFAULT; the gallery's files (400).
 *
 * The changes go to the aggregate in an order that never passes through an impossible state: (1) back into the shop,
 * (2) price, position, default and barcode, (3) the gallery, (4) out of the shop. So "make this inactive color active
 * and the default" works in one request, and the last-active-color rule is checked on the final state (422
 * LAST_ACTIVE_VARIANT). `isDefault: false` never takes the default away (make another color the default). The same
 * values again record no event and no change log row; a change logs one VARIANT_UPDATED with what changed.
 */
class UpdateVariantUc(
    private val transactionManager: TransactionManager,
    private val productRepository: ProductRepository,
    private val productInput: ProductInput,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateVariantUc.Cmd, VariantId, UpdateVariantUc.Failure> {

    private val logger = getLogger()

    /** @property productId / [variantId] the path's parameters as they came. */
    data class Cmd(val productId: String, val variantId: String, val variant: ProductInput.Variant, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<VariantId, Failure> {
        logger.info("UpdateVariantUc.execute product=${command.productId} variant=${command.variantId} by=${command.actor.userId.value}")
        // The stock is never set here: the form's starting stock is ignored, not checked.
        val form = productInput.checkVariant(command.variant.copy(stockQuantity = null))
            .unwrapOr { return Result.failure(Failure.InvalidInput(it.causes.flattenGroups())) }
        val productId = command.productId.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.ProductNotFound)
        val variantId = command.variantId.toLongOrNull()?.let { VariantId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.VariantNotFound)

        return transactionManager.inTransaction {
            val product = productRepository.get(productId) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val before = product.findVariant(variantId) ?: return@inTransaction Result.failure(Failure.VariantNotFound)
            if (form.colorKey != before.colorKey) return@inTransaction Result.failure(Failure.ColorKeyImmutable)
            if (form.sku != null && form.sku != before.sku) return@inTransaction Result.failure(Failure.SkuImmutable)
            if (product.status == ProductStatus.ARCHIVED) return@inTransaction Result.failure(Failure.ProductArchived)
            if (form.isDefault && !form.isActive) return@inTransaction Result.failure(Failure.InactiveCannotBeDefault)
            val files = mediaAttachments.check(
                form.gallery.map { MediaAttachments.Wanted(it.mediaId, form.mediaFieldOf(it.mediaId)) },
                MediaPurpose.PRODUCT,
                setOf(MediaType.IMAGE, MediaType.VIDEO),
            ).unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }

            val now = clock.instant()
            val by = command.actor.userId
            if (form.isActive && !before.isActive) product.reactivateVariant(variantId, by, now).unwrapOr { error("Checked: an inactive color of an editable product ($it)") }
            product.changeVariant(variantId, form.ownPriceTag, form.sortOrder ?: before.sortOrder, form.isDefault, by, now, form.barcode)
                .unwrapOr { error("Checked: active when asked to be the default ($it)") }
            val media = form.gallery.map { ProductMedia.of(it.mediaId, files.getValue(it.mediaId).type, it.view, it.alt) }
            product.replaceVariantMedia(variantId, media, by, now).unwrapOr { error("Checked: an editable product's color ($it)") }
            if (!form.isActive && before.isActive) {
                product.deactivateVariant(variantId, by, now).unwrapOr {
                    when (it) {
                        Product.DeactivateVariantFailure.LastActiveVariant -> return@inTransaction Result.failure(Failure.LastActiveVariant)
                        else -> error("Checked: an active color of an editable product ($it)")
                    }
                }
            }
            if (product.getRecordedEvents().isEmpty()) return@inTransaction Result.success(variantId)

            productRepository.save(product)
            mediaAttachments.attach(files.keys, now)
            val after = product.findVariant(variantId) ?: error("Still on the product")
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.VARIANT_UPDATED, AuditEntityType.VARIANT, variantId.value.toString(),
                    variantLabel(product.details.name.value, after.colorKey), changesOf(before, after),
                ),
            )
            Result.success(variantId)
        }
    }

    private fun changesOf(before: Variant, after: Variant) = AuditDiff.build {
        field("priceOverride", before.ownPriceTag?.price?.toman, after.ownPriceTag?.price?.toman)
        field("compareAtPriceOverride", before.ownPriceTag?.compareAtPrice?.toman, after.ownPriceTag?.compareAtPrice?.toman)
        field("sortOrder", before.sortOrder, after.sortOrder)
        field("isDefault", before.isDefault, after.isDefault)
        field("isActive", before.isActive, after.isActive)
        field("barcode", before.barcode?.value, after.barcode?.value)
        list("media", before.media.map { it.mediaId.value }, after.media.map { it.mediaId.value })
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object VariantNotFound : Failure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object ColorKeyImmutable : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(Immutable("COLOR_KEY_IMMUTABLE", "colorKey")))

        data object SkuImmutable : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(Immutable("SKU_IMMUTABLE", "sku")))

        data object ProductArchived : Failure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data object InactiveCannotBeDefault : Failure("INACTIVE_VARIANT_CANNOT_BE_DEFAULT", FailureKind.BUSINESS_RULE)

        data object LastActiveVariant : Failure("LAST_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE)
    }

    private class Immutable(code: String, field: String) : ResultFailure(code, FailureKind.INVALID, field)
}
