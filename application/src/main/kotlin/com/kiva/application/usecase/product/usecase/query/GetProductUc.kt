package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.usecase.product.ProductCards
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaType
import java.time.Clock

/**
 * One product's page (`GET /products/{idOrSlug}`), asked for by its number (`1`), its code (`KV-1001`) or its slug
 * (`mahak-shoulder-bag`); ProductRef tells which.
 *
 * Only products in the shop have a page: a draft, an archived product and a reference that names nothing are all
 * PRODUCT_NOT_FOUND, so the shop never tells which of them it was. A sold-out product keeps its page; its colors say
 * they are sold out. `?color=` only picks the color shown first: one the product doesn't come in falls back to the
 * default, as an old link may name a color taken out of the shop.
 *
 * The card part is made by ProductCards, exactly as in the list.
 */
class GetProductUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val materialRepository: MaterialRepository,
    private val colorRepository: ColorRepository,
    private val clock: Clock,
    private val freeShippingThreshold: Money,
) : QueryUseCase<GetProductUc.Qry, ProductDetailDto, GetProductUc.Failure> {

    private val logger = getLogger()

    /** The path segment and `?color=` as they arrived. */
    data class Qry(val idOrSlug: String, val color: String? = null)

    override suspend fun execute(query: Qry): Result<ProductDetailDto, Failure> {
        logger.info("GetProductUc.execute idOrSlug=${query.idOrSlug} color=${query.color}")

        // A malformed color is a client bug worth seeing (400); an empty one is no color.
        val asked = query.color?.trim()?.ifEmpty { null }?.let { raw ->
            ColorKey.makeNew(raw).forField("color").unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }
        }
        // A malformed reference can't name any product: not found, before any transaction.
        val ref = ProductRef.parse(query.idOrSlug) ?: return Result.failure(Failure.ProductNotFound)
        val now = clock.instant()

        return transactionManager.inTransaction {
            val product = productQmRepository.findDetail(ref) ?: return@inTransaction Result.failure(Failure.ProductNotFound)

            // The read model's foreign keys keep these three lookups whole.
            val category = categoryRepository.findBySlug(product.card.categorySlug) ?: error("No category ${product.card.categorySlug.value}")
            val material = materialRepository.findAll().first { it.slug == product.materialSlug }
            val palette = colorRepository.findAll().associateBy { it.key }
            val card = ProductCards.cardOf(product.card, category, palette, now, freeShippingThreshold)

            Result.success(
                ProductDetailDto(
                    card = card,
                    selectedColorKey = asked?.takeIf { key -> product.variants.any { it.colorKey == key } }?.value ?: card.defaultColorKey,
                    shortDescription = product.shortDescription?.value,
                    description = product.description?.value,
                    highlights = product.highlights.items,
                    material = ProductDetailDto.MaterialRef(material.id.value, material.slug.value, material.name.value, material.family),
                    materialDescription = product.materialDescription?.value,
                    specs = product.specs,
                    variants = product.variants.map { variantOf(it, palette, product.card.maxPerOrder) },
                    returnable = product.returnable,
                    maxPerOrder = product.card.maxPerOrder.value,
                    publishedAt = product.publishedOn,
                    updatedAt = product.updatedOn,
                ),
            )
        }
    }

    /** One color: its price tag, and its stock through StockPolicy, so only a few left shows a number. */
    private fun variantOf(variant: ProductDetailQm.Variant, palette: Map<ColorKey, Color>, maxPerOrder: MaxPerOrder): ProductDetailDto.Variant {
        val swatch = palette.getValue(variant.colorKey)
        return ProductDetailDto.Variant(
            id = variant.variantId.value,
            sku = variant.sku.value,
            color = ProductDetailDto.Swatch(swatch.key.value, swatch.name.value, swatch.hex.value, swatch.isLight, swatch.sortOrder),
            isDefault = variant.isDefault,
            price = PriceDto.from(variant.priceTag),
            stock = StockPolicy.stockOf(variant.available, maxPerOrder),
            media = variant.media,
            hasVideo = variant.media.any { it.type == MediaType.VIDEO },
        )
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        causes: List<ResultFailure> = emptyList(),
    ) : ResultFailure(code, kind, causes = causes) {
        /** `?color=` is not a color key (400 VALIDATION_ERROR on `color`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
