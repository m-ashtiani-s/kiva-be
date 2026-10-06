package com.kiva.domain.aggregate.product

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Instant

/**
 * The product rules that need something outside one product, so the Product aggregate can't keep them alone:
 * - **the things a product points to exist**: its category, its material, each color of the palette
 * - **new ids** for a product and its colors, handed out before they are created
 *
 * The rest of a product's rules live in [Product]. Operations that need neither a lookup nor a new id (price, status,
 * changing or deactivating a color, its gallery) are called on the aggregate directly by the use cases, as the skill's
 * Rule 1 allows for single-aggregate operations.
 *
 * It never saves and never opens a transaction: the use case loads, calls this, saves, all in its one transaction.
 * Slug and SKU being unique across products is not checked here either: the use case asks the read model and the
 * database's unique index has the last word.
 */
class ProductDomainService(
    private val productRepository: ProductRepository,
    private val productIdGenerator: ProductIdGenerator,
    private val categoryRepository: CategoryRepository,
    private val materialRepository: MaterialRepository,
    private val colorRepository: ColorRepository,
) {
    private val logger = getLogger()

    /**
     * One color to add to a product.
     *
     * @property sku null to make one from the product's (`KV-1001-LILAC`).
     * @property ownPriceTag null to follow the product's price.
     * @property sortOrder null to put it after the colors already there.
     */
    data class NewVariant(
        val colorKey: ColorKey,
        val sku: Sku?,
        val ownPriceTag: PriceTag?,
        val makeDefault: Boolean,
        val sortOrder: Int?,
        val media: List<ProductMedia>,
    )

    fun getProduct(id: ProductId): Result<Product, GetProductFailure> {
        logger.info("getProduct productId=${id.value}")
        val product = productRepository.get(id) ?: return Result.failure(GetProductFailure.ProductNotFound(id))
        return Result.success(product)
    }

    /**
     * A new draft product with its colors, in the given order. Every color is its own event right after the product's,
     * so one call records 1 + (number of colors) events.
     */
    fun createProduct(
        slug: Slug,
        sku: Sku?,
        details: ProductDetails,
        priceTag: PriceTag,
        variants: List<NewVariant>,
        by: UserId,
        now: Instant,
    ): Result<Product, CreateProductFailure> {
        logger.info("createProduct slug=${slug.value} colors=${variants.map { it.colorKey.value }}")

        if (categoryRepository.findById(details.categoryId) == null) {
            return Result.failure(CreateProductFailure.CategoryNotFound(details.categoryId))
        }
        if (materialRepository.findById(details.materialId) == null) {
            return Result.failure(CreateProductFailure.MaterialNotFound(details.materialId))
        }
        variants.firstOrNull { colorRepository.findByKey(it.colorKey) == null }?.let {
            return Result.failure(CreateProductFailure.ColorNotFound(it.colorKey))
        }

        val product = Product.create(productIdGenerator.nextProductId(), slug, sku, details, priceTag, by, now)
            .unwrapOr { error("Product.create can't fail") }

        variants.forEachIndexed { index, variant ->
            product.addVariant(
                variantId = productIdGenerator.nextVariantId(),
                colorKey = variant.colorKey,
                sku = variant.sku,
                ownPriceTag = variant.ownPriceTag,
                makeDefault = variant.makeDefault,
                sortOrder = variant.sortOrder ?: index,
                media = variant.media,
                by = by,
                now = now,
            ).unwrapOr { failure ->
                return Result.failure(
                    when (failure) {
                        is Product.AddVariantFailure.ColorAlreadyAdded -> CreateProductFailure.ColorRepeated(variant.colorKey)
                        Product.AddVariantFailure.SkuAlreadyUsed -> CreateProductFailure.VariantSkuRepeated(variant.colorKey)
                        Product.AddVariantFailure.ProductIsArchived -> error("A new product is never archived")
                    },
                )
            }
        }
        return Result.success(product)
    }

    /** New content for the product form; its category and material must exist. */
    fun changeDetails(product: Product, details: ProductDetails, by: UserId, now: Instant): Result<Product, ChangeDetailsFailure> {
        logger.info("changeDetails productId=${product.id.value}")

        if (categoryRepository.findById(details.categoryId) == null) {
            return Result.failure(ChangeDetailsFailure.CategoryNotFound(details.categoryId))
        }
        if (materialRepository.findById(details.materialId) == null) {
            return Result.failure(ChangeDetailsFailure.MaterialNotFound(details.materialId))
        }
        return product.changeDetails(details, by, now).mapOnFailure {
            when (it) {
                Product.ChangeDetailsFailure.ProductIsArchived -> ChangeDetailsFailure.ProductIsArchived
            }
        }
    }

    /** Adds one color of the palette to an existing product, with a new id. */
    fun addVariant(product: Product, variant: NewVariant, by: UserId, now: Instant): Result<Product, AddVariantFailure> {
        logger.info("addVariant productId=${product.id.value} color=${variant.colorKey.value}")

        if (colorRepository.findByKey(variant.colorKey) == null) {
            return Result.failure(AddVariantFailure.ColorNotFound(variant.colorKey))
        }
        val sortOrder = variant.sortOrder ?: ((product.variants.maxOfOrNull { it.sortOrder } ?: -1) + 1)

        return product.addVariant(
            variantId = productIdGenerator.nextVariantId(),
            colorKey = variant.colorKey,
            sku = variant.sku,
            ownPriceTag = variant.ownPriceTag,
            makeDefault = variant.makeDefault,
            sortOrder = sortOrder,
            media = variant.media,
            by = by,
            now = now,
        ).mapOnFailure {
            when (it) {
                Product.AddVariantFailure.ProductIsArchived -> AddVariantFailure.ProductIsArchived
                is Product.AddVariantFailure.ColorAlreadyAdded -> AddVariantFailure.ColorAlreadyAdded(it.colorKey)
                Product.AddVariantFailure.SkuAlreadyUsed -> AddVariantFailure.SkuAlreadyUsed
            }
        }
    }

    // ============ failures ============
    // A reference to something that doesn't exist is an input problem on its field (400 VALIDATION_ERROR).

    sealed class GetProductFailure(code: String, kind: FailureKind, meta: Map<String, Any>) : ResultFailure(code, kind, meta = meta) {
        data class ProductNotFound(val productId: ProductId) :
            GetProductFailure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND, mapOf("productId" to productId.value))
    }

    sealed class CreateProductFailure(code: String, kind: FailureKind, field: String, meta: Map<String, Any>) :
        ResultFailure(code, kind, field, meta = meta) {
        data class CategoryNotFound(val categoryId: CategoryId) :
            CreateProductFailure("CATEGORY_NOT_FOUND", FailureKind.INVALID, "categoryId", mapOf("categoryId" to categoryId.value))

        data class MaterialNotFound(val materialId: MaterialId) :
            CreateProductFailure("MATERIAL_NOT_FOUND", FailureKind.INVALID, "materialId", mapOf("materialId" to materialId.value))

        data class ColorNotFound(val colorKey: ColorKey) :
            CreateProductFailure("COLOR_NOT_FOUND", FailureKind.INVALID, "colorKey", mapOf("colorKey" to colorKey.value))

        /** The same color twice in the list of colors. */
        data class ColorRepeated(val colorKey: ColorKey) :
            CreateProductFailure("COLOR_ALREADY_ON_PRODUCT", FailureKind.CONFLICT, "colorKey", mapOf("colorKey" to colorKey.value))

        /** Two colors (or a color and the product) given the same typed SKU. */
        data class VariantSkuRepeated(val colorKey: ColorKey) :
            CreateProductFailure("DUPLICATE_SKU", FailureKind.CONFLICT, "sku", mapOf("colorKey" to colorKey.value))
    }

    sealed class ChangeDetailsFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data class CategoryNotFound(val categoryId: CategoryId) :
            ChangeDetailsFailure("CATEGORY_NOT_FOUND", FailureKind.INVALID, "categoryId", mapOf("categoryId" to categoryId.value))

        data class MaterialNotFound(val materialId: MaterialId) :
            ChangeDetailsFailure("MATERIAL_NOT_FOUND", FailureKind.INVALID, "materialId", mapOf("materialId" to materialId.value))

        data object ProductIsArchived : ChangeDetailsFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)
    }

    sealed class AddVariantFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data class ColorNotFound(val colorKey: ColorKey) :
            AddVariantFailure("COLOR_NOT_FOUND", FailureKind.INVALID, "colorKey", mapOf("colorKey" to colorKey.value))

        data object ProductIsArchived : AddVariantFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class ColorAlreadyAdded(val colorKey: ColorKey) :
            AddVariantFailure("COLOR_ALREADY_ON_PRODUCT", FailureKind.CONFLICT, "colorKey", mapOf("colorKey" to colorKey.value))

        data object SkuAlreadyUsed : AddVariantFailure("DUPLICATE_SKU", FailureKind.CONFLICT, "sku")
    }
}
