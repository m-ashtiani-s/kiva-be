package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
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
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.ProductDomainService.NewVariant
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductCodeTakenException
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock

/**
 * The panel adds a color to a product (`POST /admin/products/{productId}/variants`; ADMIN, CATALOG_MANAGER).
 *
 * The form first (400, every problem at once, ProductInput). Then one transaction, in the contract's order: the product
 * exists (404), it is not archived (422), the color is in the palette (400 COLOR_NOT_FOUND on colorKey), it is not on
 * the product already, even inactive (409 COLOR_ALREADY_ON_PRODUCT), its code is nobody else's (409 DUPLICATE_SKU),
 * then the gallery's files (400, MediaAttachments). Then it is added, taken out of the shop at once if the form says
 * `isActive: false`, saved (a code taken by another request at the same moment is the same 409), its starting stock
 * written as one RESTOCK in the same transaction, its files marked used, and one VARIANT_ADDED row logged.
 */
class AddVariantUc(
    private val transactionManager: TransactionManager,
    private val productDomainService: ProductDomainService,
    private val productRepository: ProductRepository,
    private val productQmRepository: ProductQmRepository,
    private val colorRepository: ColorRepository,
    private val productInput: ProductInput,
    private val mediaAttachments: MediaAttachments,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<AddVariantUc.Cmd, AddVariantUc.Added, AddVariantUc.Failure> {

    private val logger = getLogger()

    /** @property productId the path's `{productId}` as it came. */
    data class Cmd(val productId: String, val variant: ProductInput.Variant, val actor: PanelActor)

    data class Added(val productId: ProductId, val variantId: VariantId)

    override suspend fun execute(command: Cmd): Result<Added, Failure> {
        logger.info("AddVariantUc.execute product=${command.productId} color=${command.variant.colorKey} by=${command.actor.userId.value}")
        val form = productInput.checkVariant(command.variant).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes.flattenGroups())) }
        val id = command.productId.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.ProductNotFound)

        return transactionManager.inTransaction {
            val product = productRepository.get(id) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            if (product.status == ProductStatus.ARCHIVED) return@inTransaction Result.failure(Failure.ProductArchived)
            colorRepository.findByKey(form.colorKey) ?: return@inTransaction Result.failure(Failure.ColorNotFound(form.colorKey))
            if (product.variants.any { it.colorKey == form.colorKey }) return@inTransaction Result.failure(Failure.ColorAlreadyOnProduct(form.colorKey))
            val sku = form.sku ?: Sku.forVariant(product.sku, form.colorKey)
            if (sku == product.sku || product.variants.any { it.sku == sku } || productQmRepository.findTakenSkus(listOf(sku)).isNotEmpty()) {
                return@inTransaction Result.failure(Failure.DuplicateSku(sku.value))
            }
            val files = mediaAttachments.check(
                form.gallery.map { MediaAttachments.Wanted(it.mediaId, form.mediaFieldOf(it.mediaId)) },
                MediaPurpose.PRODUCT,
                setOf(MediaType.IMAGE, MediaType.VIDEO),
            ).unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }

            val now = clock.instant()
            val by = command.actor.userId
            val media = form.gallery.map { ProductMedia.of(it.mediaId, files.getValue(it.mediaId).type, it.view, it.alt) }
            productDomainService.addVariant(product, NewVariant(form.colorKey, form.sku, form.ownPriceTag, form.isDefault, form.sortOrder, media, form.barcode), by, now)
                .unwrapOr { error("Checked just above: $it") }
            val variant = product.variants.last()
            if (!form.isActive) product.deactivateVariant(variant.id, by, now).unwrapOr { error("A color just added can be taken out: $it") }
            try {
                productRepository.save(product)
            } catch (e: ProductCodeTakenException) {
                logger.info("AddVariantUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateSku(sku.value))
            }

            if (form.stockQuantity > 0) {
                val delta = StockDelta.makeNew(form.stockQuantity).unwrapOr { error("Checked by ProductInput: $it") }
                val inventory = inventoryDomainService.adjustStock(variant.id, delta, AdjustmentReason.RESTOCK, null, by, now)
                    .unwrapOr { error("A restock of a new color can't be refused: $it") }
                inventoryRepository.save(inventory)
            }
            mediaAttachments.attach(files.keys, now)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.VARIANT_ADDED, AuditEntityType.VARIANT, variant.id.value.toString(),
                    variantLabel(product.details.name.value, variant.colorKey), null,
                ),
            )
            Result.success(Added(id, variant.id))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object ProductArchived : Failure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class ColorNotFound(val colorKey: ColorKey) :
            Failure("COLOR_NOT_FOUND", FailureKind.INVALID, "colorKey", meta = mapOf("colorKey" to colorKey.value))

        data class ColorAlreadyOnProduct(val colorKey: ColorKey) :
            Failure("COLOR_ALREADY_ON_PRODUCT", FailureKind.CONFLICT, "colorKey", meta = mapOf("colorKey" to colorKey.value))

        data class DuplicateSku(val sku: String) : Failure("DUPLICATE_SKU", FailureKind.CONFLICT, "sku", meta = mapOf("sku" to sku))
    }
}

/** How a color shows in the change log: the product's name and the color's key («کیف دوشی ماهک · lilac»). */
internal fun variantLabel(productName: String, colorKey: ColorKey): String = "$productName · ${colorKey.value}"
