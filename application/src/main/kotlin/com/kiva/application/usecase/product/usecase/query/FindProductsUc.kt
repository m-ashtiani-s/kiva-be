package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.railway.zipAll
import com.kiva.common.text.normalizeDigits
import com.kiva.common.text.normalizePersian
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.policy.BadgePolicy
import com.kiva.domain.aggregate.product.model.policy.PricePolicy
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Clock
import java.time.Instant

/**
 * The shop's product list (`GET /products`): filtered, sorted and paged, as product cards.
 *
 * Every filter is checked at once, before any transaction: all mistakes come back together as VALIDATION_ERROR, each
 * under its parameter (`maxPrice`, `color`, `ids`). A well-formed value that names nothing (a category nobody has) is
 * not a mistake: it simply matches no product. An empty parameter (`minPrice=`) is the same as a missing one.
 *
 * The read model gives the products; their cards are made here with the shop's rules. The card shows the default
 * color's price. Its stock is the whole product's, through StockPolicy, so the exact number never leaves; its badges
 * come from BadgePolicy, «جدید» judged at this request's moment.
 */
class FindProductsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val clock: Clock,
    private val freeShippingThreshold: Money,
) : QueryUseCase<FindProductsUc.Qry, Page<ProductCardDto>, FindProductsUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's query parameters as they arrived, list parameters already split at their commas. [pageable] is
     * checked by the route, with ProductSorts as the sorts it allows.
     *
     * @property minPrice / maxPrice as typed, Persian digits included.
     * @property ids as typed: product ids, at most [MAX_IDS].
     */
    data class Qry(
        val pageable: Pageable,
        val q: String? = null,
        val categories: List<String> = emptyList(),
        val colors: List<String> = emptyList(),
        val minPrice: String? = null,
        val maxPrice: String? = null,
        val onSale: Boolean = false,
        val inStock: Boolean = false,
        val isNew: Boolean = false,
        val hasVideo: Boolean = false,
        val bagTypes: List<String> = emptyList(),
        val materials: List<String> = emptyList(),
        val occasions: List<String> = emptyList(),
        val ids: List<String> = emptyList(),
    )

    override suspend fun execute(query: Qry): Result<Page<ProductCardDto>, Failure> {
        logger.info(
            "FindProductsUc.execute q=${query.q} category=${query.categories} color=${query.colors} " +
                "sort=${query.pageable.sort} page=${query.pageable.page}",
        )

        val now = clock.instant()
        // Every filter at once, before the transaction, so bad input never takes a database connection.
        val filter = filterOf(query, now).unwrapOr { return Result.failure(it) }

        return transactionManager.inTransaction {
            val page = productQmRepository.find(filter, query.pageable)
            // Both lists are short (8 categories, 13 colors) and the cards of one page need many of them.
            val categories = categoryRepository.findAll().associateBy { it.slug }
            val palette = colorRepository.findAll().associateBy { it.key }
            Result.success(page.map { cardOf(it, categories, palette, now) })
        }
    }

    // ============ checking the filters ============

    private fun filterOf(query: Qry, now: Instant): Result<ProductFilter, Failure.InvalidInput> = Result.zip(
        Result.zip(wordsOf(query.q), idsOf(query.ids), ::InputProblems) { words, ids -> words to ids },
        Result.zip(
            each(query.categories, "category") { Slug.makeNew(it) },
            each(query.colors, "color") { ColorKey.makeNew(it) },
            each(query.materials, "material") { Slug.makeNew(it) },
            ::InputProblems,
        ) { categories, colors, materials -> Triple(categories, colors, materials) },
        Result.zip(
            each(query.bagTypes, "bagType") { BagType.makeNew(it) },
            each(query.occasions, "occasion") { Occasion.makeNew(it) },
            ::InputProblems,
        ) { bagTypes, occasions -> bagTypes to occasions },
        priceRangeOf(query.minPrice, query.maxPrice),
        { problems -> Failure.InvalidInput(problems.flattenGroups()) },
    ) { (words, ids), (categories, colors, materials), (bagTypes, occasions), price ->
        ProductFilter(
            words = words,
            categories = categories.toSet(),
            colors = colors.toSet(),
            price = price,
            onSale = query.onSale,
            inStock = query.inStock,
            newAt = now.takeIf { query.isNew },
            hasVideo = query.hasVideo,
            bagTypes = bagTypes.toSet(),
            materials = materials.toSet(),
            occasions = occasions.toSet(),
            ids = ids,
        )
    }

    /** The search text in words, normalized like the read model's searchText: «كيف» typed on an Arabic keyboard finds «کیف». */
    private fun wordsOf(q: String?): Result<List<String>, ResultFailure> {
        val text = q?.trim().orEmpty()
        if (text.length > MAX_QUERY_LENGTH) return Result.failure(Failure.SearchTooLong)
        return Result.success(text.normalizePersian().lowercase().split(' ').filter { it.isNotEmpty() })
    }

    private fun idsOf(raws: List<String>): Result<List<ProductId>, ResultFailure> {
        if (raws.size > MAX_IDS) return Result.failure(Failure.TooManyIds)
        return each(raws, "ids") { raw -> numberOf(raw)?.let { ProductId.makeNew(it) } ?: Result.failure(Failure.NotANumber) }
    }

    /** Both ends first, then the rule between them. */
    private fun priceRangeOf(minPrice: String?, maxPrice: String?): Result<PriceRange, ResultFailure> {
        val ends: Result<Pair<Money?, Money?>, ResultFailure> =
            Result.zip(moneyOf(minPrice, "minPrice"), moneyOf(maxPrice, "maxPrice"), ::InputProblems) { min, max -> min to max }
        return ends.bind { (min, max) -> PriceRange.makeNew(min, max) }
    }

    private fun moneyOf(raw: String?, parameter: String): Result<Money?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        val amount = numberOf(text) ?: return Result.failure(Failure.NotANumber).forField(parameter)
        return Money.makeNew(amount).forField(parameter)
    }

    /** Every value of a list parameter, each problem under the parameter's name. */
    private fun <T> each(raws: List<String>, parameter: String, parse: (String) -> Result<T, ResultFailure>): Result<List<T>, ResultFailure> =
        Result.zipAll(raws.map { parse(it).forField(parameter) }, ::InputProblems)

    private fun numberOf(raw: String): Long? = raw.normalizeDigits().trim().toLongOrNull()

    // ============ the cards ============

    private fun cardOf(qm: ProductCardQm, categories: Map<Slug, Category>, palette: Map<ColorKey, Color>, now: Instant): ProductCardDto {
        // The read model's foreign keys keep both lookups whole.
        val category = categories.getValue(qm.categorySlug)
        val stock = StockPolicy.stockOf(qm.colors.sumOf { it.available }, qm.maxPerOrder)
        val isNew = BadgePolicy.isNew(qm.newUntil, now)

        return ProductCardDto(
            id = qm.productId.value,
            slug = qm.slug.value,
            sku = qm.sku.value,
            name = qm.name.value,
            category = ProductCardDto.CategoryRef(category.id.value, category.slug.value, category.name.value),
            bagType = qm.bagType,
            price = PriceDto.from(qm.priceTag),
            colors = qm.colors.map { color ->
                val swatch = palette.getValue(color.colorKey)
                ProductCardDto.ColorOption(
                    variantId = color.variantId.value,
                    key = swatch.key.value,
                    name = swatch.name.value,
                    hex = swatch.hex.value,
                    isLight = swatch.isLight,
                    sortOrder = swatch.sortOrder,
                    stockStatus = StockPolicy.stockOf(color.available, qm.maxPerOrder).status,
                )
            },
            // A product in the shop always has an active default color: Product keeps that.
            defaultColorKey = qm.colors.first { it.isDefault }.colorKey.value,
            stock = stock,
            badges = BadgePolicy.badgesOf(isNew, qm.priceTag, qm.hasVideo, stock.status, freeShippingThreshold),
            isNew = isNew,
            hasVideo = qm.hasVideo,
            soldCount = qm.soldCount,
            freeShippingEligible = PricePolicy.qualifiesForFreeShipping(qm.priceTag, freeShippingThreshold),
        )
    }

    companion object {
        /** From the contract's `q`. */
        const val MAX_QUERY_LENGTH = 100

        /** From the contract's `ids`. */
        const val MAX_IDS = 60
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, field, causes, meta) {
        /** One or more parameters are wrong; each cause names its parameter (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) :
            Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object SearchTooLong :
            Failure("SEARCH_QUERY_TOO_LONG", FailureKind.INVALID, "q", meta = mapOf("maxLength" to MAX_QUERY_LENGTH))

        data object TooManyIds : Failure("IDS_TOO_MANY", FailureKind.INVALID, "ids", meta = mapOf("maxItems" to MAX_IDS))

        /** Not a whole number. No field here: the caller names the parameter. */
        data object NotANumber : Failure("NUMBER_INVALID", FailureKind.INVALID)
    }
}
