package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.dto.CreatedProductDto
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
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.railway.zipAll
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.ProductDomainService.CreateProductFailure
import com.kiva.domain.aggregate.product.ProductDomainService.NewVariant
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.repository.ProductCodeTakenException
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock
import java.time.Instant

/**
 * A new product with its colors, their galleries and their starting stock (`POST /admin/products`; ADMIN,
 * CATALOG_MANAGER; and the dev seeder).
 *
 * The form first, before any transaction (ProductInput): every mistake comes back together as VALIDATION_ERROR, each
 * under its path (`specs.straps.0.lengthCm`, `variants.1.media.0.mediaId`). Then one transaction:
 *
 * 1. the slug is free (409 DUPLICATE_SLUG); first, so for the seeder a slug in use means "made already";
 * 2. every file of every gallery is an uploaded PRODUCT photo or video (400 MEDIA_NOT_FOUND / MEDIA_PURPOSE_MISMATCH);
 * 3. the domain builds the draft and its colors (category, material and colors must exist; a color twice is 409);
 * 4. the codes it ended up with, typed or made from the id, are free (409 DUPLICATE_SKU);
 * 5. colors the form sent with `isActive: false` are taken out of the shop, then `status` can publish it (422
 *    NO_ACTIVE_VARIANT without an active color) or archive it;
 * 6. it is saved; a second request with the same slug or code at the same moment is refused by the read model's
 *    unique index and gets the same 409 as the check would have given (ProductCodeTakenException), never a 500;
 * 7. the starting stock of each color is one RESTOCK, in the same transaction, so a product is never left without
 *    the stock its form asked for;
 * 8. the files are marked used, and one PRODUCT_CREATED row goes in the change log.
 */
class CreateProductUc(
    private val transactionManager: TransactionManager,
    private val productDomainService: ProductDomainService,
    private val productRepository: ProductRepository,
    private val productQmRepository: ProductQmRepository,
    private val productInput: ProductInput,
    private val mediaAttachments: MediaAttachments,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateProductUc.Cmd, CreatedProductDto, CreateProductUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's ProductUpsertRequest as it arrived, plus who makes it.
     *
     * @property status null for the contract's default, DRAFT.
     * @property actor from the access token and the request; null for the seeder, which acts as [UserId.SYSTEM] and
     *   writes no change log (the system's work never does).
     */
    data class Cmd(
        val fields: ProductInput.Fields,
        val status: String?,
        val variants: List<ProductInput.Variant>,
        val actor: PanelActor?,
    )

    /** The form, checked and ready for the domain. */
    private data class Input(val product: ProductInput.Checked, val status: ProductStatus, val variants: List<ProductInput.CheckedVariant>)

    override suspend fun execute(command: Cmd): Result<CreatedProductDto, Failure> {
        logger.info("CreateProductUc.execute slug=${command.fields.slug} colors=${command.variants.map { it.colorKey }} by=${command.actor?.userId?.value}")
        val by = command.actor?.userId ?: UserId.SYSTEM

        val input = Result.zip(
            productInput.check(command.fields),
            statusOf(command.status).forField("status"),
            Result.zipAll(command.variants.mapIndexed { index, variant -> productInput.checkVariant(variant).forField("variants.$index") }, ::InputProblems),
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
            ::Input,
        ).unwrapOr { return Result.failure(it) }

        return transactionManager.inTransaction {
            if (productQmRepository.isSlugTaken(input.product.slug)) return@inTransaction Result.failure(Failure.DuplicateSlug(input.product.slug))

            val files = mediaAttachments.check(wantedFiles(input.variants), MediaPurpose.PRODUCT, setOf(MediaType.IMAGE, MediaType.VIDEO))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }

            val now = clock.instant()
            val newVariants = input.variants.map { newVariantOf(it, files) }
            val product = productDomainService
                .createProduct(input.product.slug, input.product.sku, input.product.details, input.product.priceTag, newVariants, by, now)
                .mapOnFailure { failureOf(it, input.variants) }
                .unwrapOr { return@inTransaction Result.failure(it) }

            // The codes the product ended up with, typed or made from its id, must not be another product's.
            val codes = listOf(product.sku to "sku") + product.variants.mapIndexed { index, it -> it.sku to "variants.$index.sku" }
            val taken = productQmRepository.findTakenSkus(codes.map { it.first })
            codes.firstOrNull { it.first in taken }?.let { (sku, field) ->
                return@inTransaction Result.failure(Failure.DuplicateSku(field, sku.value))
            }

            // Colors are added in the form's order, so the n-th color of the product is the n-th of the form.
            product.variants.zip(input.variants).filterNot { (_, form) -> form.isActive }.forEach { (variant, _) ->
                product.deactivateVariant(variant.id, by, now).unwrapOr { error("A new draft's active color can be taken out: $it") }
            }
            moveTo(product, input.status, by, now).unwrapOr { return@inTransaction Result.failure(it) }
            try {
                productRepository.save(product)
            } catch (e: ProductCodeTakenException) {
                logger.info("CreateProductUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(takenFailureOf(e, codes))
            }

            // In the colors' id order, the order every stock writer takes (no deadlock with an order being placed).
            product.variants.zip(input.variants).filter { (_, form) -> form.stockQuantity > 0 }.sortedBy { (variant, _) -> variant.id.value }
                .forEach { (variant, form) ->
                    val delta = StockDelta.makeNew(form.stockQuantity).unwrapOr { error("Checked by ProductInput: $it") }
                    val inventory = inventoryDomainService.adjustStock(variant.id, delta, AdjustmentReason.RESTOCK, null, by, now)
                        .unwrapOr { error("A restock of a new color can't be refused: $it") }
                    inventoryRepository.save(inventory)
                }

            mediaAttachments.attach(files.keys, now)
            command.actor?.let { actor ->
                auditTrail.record(
                    AuditEntry(now, actor, AuditAction.PRODUCT_CREATED, AuditEntityType.PRODUCT, product.id.value.toString(), product.details.name.value, null),
                )
            }
            Result.success(CreatedProductDto(product.id.value, product.variants.associate { it.colorKey.value to it.id.value }))
        }
    }

    private fun wantedFiles(variants: List<ProductInput.CheckedVariant>): List<MediaAttachments.Wanted> =
        variants.flatMapIndexed { index, variant ->
            variant.gallery.map { MediaAttachments.Wanted(it.mediaId, "variants.$index.${variant.mediaFieldOf(it.mediaId)}") }
        }

    /** The gallery's type is the file's own (a photo or a video), never the client's word. */
    private fun newVariantOf(variant: ProductInput.CheckedVariant, files: Map<MediaId, MediaAsset>) = NewVariant(
        colorKey = variant.colorKey,
        sku = variant.sku,
        ownPriceTag = variant.ownPriceTag,
        makeDefault = variant.isDefault,
        sortOrder = variant.sortOrder,
        media = variant.gallery.map { ProductMedia.of(it.mediaId, files.getValue(it.mediaId).type, it.view, it.alt) },
        barcode = variant.barcode,
    )

    /** The failure the check before the save would have answered, had the other product been saved a moment sooner. */
    private fun takenFailureOf(taken: ProductCodeTakenException, codes: List<Pair<Sku, String>>): Failure = when (taken) {
        is ProductCodeTakenException.SlugTaken -> Failure.DuplicateSlug(taken.slug)
        is ProductCodeTakenException.SkuTaken -> Failure.DuplicateSku(codes.firstOrNull { it.first == taken.sku }?.second ?: "sku", taken.sku?.value)
    }

    private fun statusOf(raw: String?): Result<ProductStatus, ResultFailure> =
        raw?.let { ProductStatus.makeNew(it) } ?: Result.success(ProductStatus.DRAFT)

    /** A new product is a draft; the contract's `status` can ask for it to be published, or archived, right away. */
    private fun moveTo(product: Product, status: ProductStatus, by: UserId, now: Instant): Result<Product, Failure> = when (status) {
        ProductStatus.DRAFT -> Result.success(product)
        ProductStatus.ACTIVE -> product.publish(by, now).mapOnFailure {
            when (it) {
                Product.PublishFailure.NoActiveVariant -> Failure.NoActiveVariant
                Product.PublishFailure.AlreadyActive, is Product.PublishFailure.InvalidStatusTransition -> error("A new product is a draft: $it")
            }
        }
        ProductStatus.ARCHIVED -> product.archive(by, now).mapOnFailure { error("A new product is a draft: $it") }
    }

    /** The domain names a color by its key; the client gets the place in its list, where the form shows it. */
    private fun failureOf(failure: CreateProductFailure, variants: List<ProductInput.CheckedVariant>): Failure {
        fun indexOf(colorKey: ColorKey, occurrence: Int = 0) =
            variants.withIndex().filter { it.value.colorKey == colorKey }.map { it.index }.getOrElse(occurrence) { 0 }

        return when (failure) {
            is CreateProductFailure.CategoryNotFound -> Failure.CategoryNotFound(failure.categoryId)
            is CreateProductFailure.MaterialNotFound -> Failure.MaterialNotFound(failure.materialId)
            is CreateProductFailure.ColorNotFound -> Failure.ColorNotFound(failure.colorKey, indexOf(failure.colorKey))
            // The second time the color appears is the one refused.
            is CreateProductFailure.ColorRepeated -> Failure.ColorRepeated(failure.colorKey, indexOf(failure.colorKey, occurrence = 1))
            is CreateProductFailure.VariantSkuRepeated -> {
                val index = indexOf(failure.colorKey)
                Failure.DuplicateSku("variants.$index.sku", variants[index].sku?.value)
            }
        }
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, field, causes, meta) {
        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) :
            Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        // A reference to something that doesn't exist is an input problem on its field (400), as in ProductDomainService.

        data class CategoryNotFound(val categoryId: CategoryId) :
            Failure("CATEGORY_NOT_FOUND", FailureKind.INVALID, "categoryId", meta = mapOf("categoryId" to categoryId.value))

        data class MaterialNotFound(val materialId: MaterialId) :
            Failure("MATERIAL_NOT_FOUND", FailureKind.INVALID, "materialId", meta = mapOf("materialId" to materialId.value))

        data class ColorNotFound(val colorKey: ColorKey, val index: Int) :
            Failure("COLOR_NOT_FOUND", FailureKind.INVALID, "variants.$index.colorKey", meta = mapOf("colorKey" to colorKey.value))

        // Something already there (409).

        data class DuplicateSlug(val slug: Slug) :
            Failure("DUPLICATE_SLUG", FailureKind.CONFLICT, "slug", meta = mapOf("slug" to slug.value))

        /** [at] is the input that has the code; [sku] is the code when it was typed or is known. */
        data class DuplicateSku(val at: String, val sku: String?) :
            Failure("DUPLICATE_SKU", FailureKind.CONFLICT, at, meta = sku?.let { mapOf("sku" to it) }.orEmpty())

        data class ColorRepeated(val colorKey: ColorKey, val index: Int) :
            Failure("COLOR_ALREADY_ON_PRODUCT", FailureKind.CONFLICT, "variants.$index.colorKey", meta = mapOf("colorKey" to colorKey.value))

        /** `status` ACTIVE for a product without an active color: the shop would show a bag nobody can pick (422). */
        data object NoActiveVariant : Failure("NO_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE, "status")
    }
}
