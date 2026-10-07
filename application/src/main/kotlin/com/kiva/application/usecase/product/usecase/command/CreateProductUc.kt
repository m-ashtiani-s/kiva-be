package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.dto.CreatedProductDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.railway.zipAll
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.ProductDomainService.CreateProductFailure
import com.kiva.domain.aggregate.product.ProductDomainService.NewVariant
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.Dimensions
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.repository.ProductCodeTakenException
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock
import java.time.Instant

/**
 * A new product with its colors (`POST /admin/products`, and the dev seeder until the admin panel exists).
 *
 * Every input is checked at once, before any transaction: all mistakes come back together as VALIDATION_ERROR, each
 * under its field (`specs.weightGrams`, `variants.1.colorKey`). Then one transaction: the slug must be free, the domain
 * builds the draft and its colors (category, material and colors must exist), the codes it ends up with (typed or made
 * from the id) must be free, and the contract's `status` can put it straight into the shop.
 *
 * Slug and codes are checked against the read model, which holds every saved product. Two requests with the same slug
 * at the same moment both pass that check; the read model's unique index then refuses the second, and the repository
 * says which code it was (ProductCodeTakenException). So the second gets the same 409 as the check would have given,
 * never a duplicate and never a 500.
 *
 * Not taken from the contract's request yet: tags and seo (nothing in the domain keeps them), and a color's
 * barcode, isActive, media (they come with uploads) and stockQuantity. Stock is its own aggregate: the starting stock is
 * one AdjustInventoryUc call per color, each in its own transaction.
 */
class CreateProductUc(
    private val transactionManager: TransactionManager,
    private val productDomainService: ProductDomainService,
    private val productRepository: ProductRepository,
    private val productQmRepository: ProductQmRepository,
    private val htmlSanitizer: HtmlSanitizer,
    private val clock: Clock,
) : CommandUseCase<CreateProductUc.Cmd, CreatedProductDto, CreateProductUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's ProductUpsertRequest as it arrived, plus who makes the change. Optional values left out (null)
     * take the contract's defaults: maxPerOrder 5, returnable, reservable, sortPriority 0, status DRAFT.
     *
     * @property sku null or blank to make one from the id (`KV-1001`).
     * @property by from the access token, or [UserId.SYSTEM] for the seeder: never typed by anyone, so not raw input.
     */
    data class Cmd(
        val name: String,
        val slug: String,
        val sku: String?,
        val categoryId: Long,
        val bagType: String,
        val materialId: Long,
        val materialDescription: String?,
        val price: Long,
        val compareAtPrice: Long?,
        val shortDescription: String?,
        val description: String?,
        val highlights: List<String>,
        val specs: Specs,
        val newUntil: Instant?,
        val maxPerOrder: Int?,
        val returnable: Boolean?,
        val reservable: Boolean?,
        val sortPriority: Int?,
        val status: String?,
        val variants: List<Variant>,
        val by: UserId,
    ) {
        /** The specs the domain keeps today (ProductSpecs); everything optional. */
        data class Specs(
            val dimensions: Dimensions?,
            val weightGrams: Int?,
            val closure: String?,
            val strap: String?,
            val pockets: String?,
            val occasions: List<String>,
        ) {
            companion object {
                val NONE = Specs(null, null, null, null, null, emptyList())
            }
        }

        /** Length × height × width, in centimeters. */
        data class Dimensions(val lengthCm: Double, val heightCm: Double, val widthCm: Double)

        /**
         * One color (the contract's VariantUpsertRequest).
         *
         * @property sku null or blank to make one from the product's (`KV-1001-LILAC`).
         * @property priceOverride null to follow the product's price; [compareAtPriceOverride] only goes with it.
         * @property sortOrder null to put it after the colors before it.
         */
        data class Variant(
            val colorKey: String,
            val sku: String?,
            val priceOverride: Long?,
            val compareAtPriceOverride: Long?,
            val isDefault: Boolean,
            val sortOrder: Int?,
        )
    }

    /** The inputs, checked and ready for the domain. */
    private data class Input(
        val slug: Slug,
        val sku: Sku?,
        val status: ProductStatus,
        val details: ProductDetails,
        val priceTag: PriceTag,
        val variants: List<NewVariant>,
    )

    override suspend fun execute(command: Cmd): Result<CreatedProductDto, Failure> {
        logger.info("CreateProductUc.execute slug=${command.slug} colors=${command.variants.map { it.colorKey }}")

        // Every input at once, so the form shows all mistakes together; before the transaction, so bad input never
        // takes a database connection.
        val input = Result.zip(
            Result.zip(Slug.makeNew(command.slug).forField("slug"), skuOf(command.sku), statusOf(command.status), ::InputProblems) { slug, sku, status ->
                Triple(slug, sku, status)
            },
            detailsOf(command),
            priceTagOf(command.price, command.compareAtPrice, "price", "compareAtPrice"),
            Result.zipAll(command.variants.mapIndexed { index, variant -> newVariantOf(variant).forField("variants.$index") }, ::InputProblems),
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
        ) { (slug, sku, status), details, priceTag, variants -> Input(slug, sku, status, details, priceTag, variants) }
            .unwrapOr { return Result.failure(it) }

        return transactionManager.inTransaction {
            // First, and before any id is taken: for the seeder a slug in use means "made already".
            if (productQmRepository.isSlugTaken(input.slug)) return@inTransaction Result.failure(Failure.DuplicateSlug(input.slug))

            val now = clock.instant()
            val product = productDomainService
                .createProduct(input.slug, input.sku, input.details, input.priceTag, input.variants, command.by, now)
                .mapOnFailure { failureOf(it, input) }
                .unwrapOr { return@inTransaction Result.failure(it) }

            // The codes the product ended up with, typed or made from its id, must not be another product's.
            val codes = listOf(product.sku to "sku") + product.variants.mapIndexed { index, it -> it.sku to "variants.$index.sku" }
            val taken = productQmRepository.findTakenSkus(codes.map { it.first })
            codes.firstOrNull { it.first in taken }?.let { (sku, field) ->
                return@inTransaction Result.failure(Failure.DuplicateSku(field, sku.value))
            }

            moveTo(product, input.status, command.by, now).unwrapOr { return@inTransaction Result.failure(it) }
            try {
                productRepository.save(product)
            } catch (e: ProductCodeTakenException) {
                logger.info("CreateProductUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(takenFailureOf(e, codes))
            }
            Result.success(CreatedProductDto(product.id.value, product.variants.associate { it.colorKey.value to it.id.value }))
        }
    }

    /** The failure the check before the save would have answered, had the other product been saved a moment sooner. */
    private fun takenFailureOf(taken: ProductCodeTakenException, codes: List<Pair<Sku, String>>): Failure = when (taken) {
        is ProductCodeTakenException.SlugTaken -> Failure.DuplicateSlug(taken.slug)
        is ProductCodeTakenException.SkuTaken -> Failure.DuplicateSku(codes.firstOrNull { it.first == taken.sku }?.second ?: "sku", taken.sku?.value)
    }

    // ============ checking the inputs ============

    private fun detailsOf(command: Cmd): Result<ProductDetails, ResultFailure> = Result.zip(
        Result.zip(
            ProductName.makeNew(command.name),
            CategoryId.makeNew(command.categoryId).forField("categoryId"),
            MaterialId.makeNew(command.materialId).forField("materialId"),
            BagType.makeNew(command.bagType),
            ::InputProblems,
        ) { name, categoryId, materialId, bagType -> Basics(name, categoryId, materialId, bagType) },
        Result.zip(
            MaterialDescription.makeNew(command.materialDescription),
            ShortDescription.makeNew(command.shortDescription),
            ProductDescription.makeNew(command.description, htmlSanitizer),
            Highlights.makeNew(command.highlights),
            ::InputProblems,
        ) { materialDescription, shortDescription, description, highlights ->
            Texts(materialDescription, shortDescription, description, highlights)
        },
        specsOf(command.specs),
        command.maxPerOrder?.let { MaxPerOrder.makeNew(it) } ?: Result.success(MaxPerOrder.DEFAULT),
        ::InputProblems,
    ) { basics, texts, specs, maxPerOrder ->
        ProductDetails(
            name = basics.name,
            categoryId = basics.categoryId,
            materialId = basics.materialId,
            materialDescription = texts.materialDescription,
            bagType = basics.bagType,
            shortDescription = texts.shortDescription,
            description = texts.description,
            highlights = texts.highlights,
            specs = specs,
            newUntil = command.newUntil,
            maxPerOrder = maxPerOrder,
            returnable = command.returnable ?: true,
            reservable = command.reservable ?: true,
            sortPriority = command.sortPriority ?: 0,
        )
    }

    private data class Basics(val name: ProductName, val categoryId: CategoryId, val materialId: MaterialId, val bagType: BagType)

    private data class Texts(
        val materialDescription: MaterialDescription?,
        val shortDescription: ShortDescription?,
        val description: ProductDescription?,
        val highlights: Highlights,
    )

    /** Every problem under `specs` (`specs.dimensions`, `specs.occasions.1`, `specs.weightGrams`). */
    private fun specsOf(specs: Cmd.Specs): Result<ProductSpecs, ResultFailure> {
        // ProductSpecs checks the weight and the texts itself. Asked here on their own, so their problems come
        // together with those of the typed parts; with everything valid, the real call below can't fail.
        val weightAndTexts = ProductSpecs.makeNew(null, specs.weightGrams, null, specs.strap, specs.pockets, emptySet())
            .mapOnFailure { InputProblems(it.causes) }

        return Result.zip(
            specs.dimensions?.let { Dimensions.makeNew(it.lengthCm, it.heightCm, it.widthCm) } ?: Result.success(null),
            specs.closure?.let { ClosureType.makeNew(it) } ?: Result.success(null),
            Result.zipAll(specs.occasions.mapIndexed { index, raw -> Occasion.makeNew(raw).forField("occasions.$index") }, ::InputProblems),
            weightAndTexts,
            ::InputProblems,
        ) { dimensions, closure, occasions, checked ->
            ProductSpecs.makeNew(dimensions, checked.weightGrams, closure, checked.strap, checked.pockets, occasions.toSet())
                .unwrapOr { error("Checked just above: $it") }
        }.forField("specs")
    }

    /** Amounts first, then the rule between them; each problem under the field it came in. */
    private fun priceTagOf(price: Long, compareAtPrice: Long?, priceField: String, compareAtField: String): Result<PriceTag, ResultFailure> {
        val amounts: Result<Pair<Money, Money?>, ResultFailure> = Result.zip(
            Money.makeNew(price).forField(priceField),
            compareAtPrice?.let { Money.makeNew(it).forField(compareAtField) } ?: Result.success(null),
            ::InputProblems,
        ) { amount, compareAt -> amount to compareAt }
        return amounts.bind { (amount, compareAt) -> PriceTag.makeNew(amount, compareAt).forField(compareAtField) }
    }

    private fun newVariantOf(variant: Cmd.Variant): Result<NewVariant, ResultFailure> = Result.zip(
        ColorKey.makeNew(variant.colorKey).forField("colorKey"),
        skuOf(variant.sku),
        ownPriceTagOf(variant),
        ::InputProblems,
    ) { colorKey, sku, ownPriceTag -> NewVariant(colorKey, sku, ownPriceTag, variant.isDefault, variant.sortOrder, media = emptyList()) }

    private fun ownPriceTagOf(variant: Cmd.Variant): Result<PriceTag?, ResultFailure> = when {
        variant.priceOverride != null ->
            priceTagOf(variant.priceOverride, variant.compareAtPriceOverride, "priceOverride", "compareAtPriceOverride")
        variant.compareAtPriceOverride != null -> Result.failure(Failure.PriceOverrideRequired)
        else -> Result.success(null)
    }

    // The contract: an empty code means "make one".
    private fun skuOf(raw: String?): Result<Sku?, ResultFailure> =
        raw?.takeIf { it.isNotBlank() }?.let { Sku.makeNew(it) } ?: Result.success(null)

    private fun statusOf(raw: String?): Result<ProductStatus, ResultFailure> =
        raw?.let { ProductStatus.makeNew(it) } ?: Result.success(ProductStatus.DRAFT)

    // ============ in the transaction ============

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
    private fun failureOf(failure: CreateProductFailure, input: Input): Failure {
        fun indexOf(colorKey: ColorKey, occurrence: Int = 0) =
            input.variants.withIndex().filter { it.value.colorKey == colorKey }.map { it.index }.getOrElse(occurrence) { 0 }

        return when (failure) {
            is CreateProductFailure.CategoryNotFound -> Failure.CategoryNotFound(failure.categoryId)
            is CreateProductFailure.MaterialNotFound -> Failure.MaterialNotFound(failure.materialId)
            is CreateProductFailure.ColorNotFound -> Failure.ColorNotFound(failure.colorKey, indexOf(failure.colorKey))
            // The second time the color appears is the one refused.
            is CreateProductFailure.ColorRepeated -> Failure.ColorRepeated(failure.colorKey, indexOf(failure.colorKey, occurrence = 1))
            is CreateProductFailure.VariantSkuRepeated -> {
                val index = indexOf(failure.colorKey)
                Failure.DuplicateSku("variants.$index.sku", input.variants[index].sku?.value)
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

        /** A crossed-out price for a color without a price of its own. */
        data object PriceOverrideRequired : Failure("PRICE_OVERRIDE_REQUIRED", FailureKind.INVALID, "priceOverride")

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

        /** `status` ACTIVE for a product without colors: the shop would show a bag nobody can pick (422). */
        data object NoActiveVariant : Failure("NO_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE, "status")
    }
}
