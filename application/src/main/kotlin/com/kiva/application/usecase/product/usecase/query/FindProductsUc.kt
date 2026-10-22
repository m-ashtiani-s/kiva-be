package com.kiva.application.usecase.product.usecase.query

import com.kiva.common.valueobject.UserId
import com.kiva.application.usecase.wishlist.WishlistHearts
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.dto.ProductListDto
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductCards
import com.kiva.application.usecase.product.ProductFilterQuery
import com.kiva.application.usecase.product.ProductFilters
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * The shop's product list (`GET /products`): filtered, sorted and paged, as product cards, with the filters named as
 * the shop shows them (for the page's title and filter chips).
 *
 * Every filter is checked at once, before any transaction (ProductFilters). The read model gives the products;
 * ProductCards makes their cards with the shop's rules, «جدید» judged at this request's moment.
 */
class FindProductsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val materialRepository: MaterialRepository,
    private val clock: Clock,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val wishlistHearts: WishlistHearts = WishlistHearts.NONE,
) : QueryUseCase<FindProductsUc.Qry, ProductListDto, FindProductsUc.Failure> {

    private val logger = getLogger()

    /**
     * [pageable] is checked by the route, with ProductSorts as the sorts it allows; [filters] as they arrived; [viewer]
     * the logged-in customer, for the hearts (none: a guest).
     */
    data class Qry(val pageable: Pageable, val filters: ProductFilterQuery = ProductFilterQuery(), val viewer: UserId? = null)

    override suspend fun execute(query: Qry): Result<ProductListDto, Failure> {
        logger.info(
            "FindProductsUc.execute q=${query.filters.q} category=${query.filters.categories} color=${query.filters.colors} " +
                "sort=${query.pageable.sort} page=${query.pageable.page}",
        )

        val now = clock.instant()
        // Every filter at once, before the transaction, so bad input never takes a database connection.
        val filter = ProductFilters.parse(query.filters, now).unwrapOr { return Result.failure(Failure.InvalidInput(it.problems)) }

        return transactionManager.inTransaction {
            val page = productQmRepository.find(filter, query.pageable)
            // All three lists are short (8 categories, 13 colors, 12 materials) and one page needs many of them.
            val categories = categoryRepository.findAll().associateBy { it.slug }
            val palette = colorRepository.findAll().associateBy { it.key }
            val materials = materialRepository.findAll().associateBy { it.slug }
            // Read on every request, so a change in the settings shows at once.
            val settings = shopSettingsRepository.get()

            val cards = page.map { ProductCards.cardOf(it, categories.getValue(it.categorySlug), palette, now, settings.freeShippingThreshold, settings.lowStockThreshold) }
            Result.success(
                ProductListDto(
                    page = Page(wishlistHearts.mark(cards.items, query.viewer), cards.page, cards.size, cards.totalItems),
                    applied = appliedOf(query.filters, filter, categories, palette, materials),
                ),
            )
        }
    }

    /** The request's filters by name, in the order they came; values that name nothing are left out. */
    private fun appliedOf(
        raw: ProductFilterQuery,
        filter: ProductFilter,
        categories: Map<Slug, Category>,
        palette: Map<ColorKey, Color>,
        materials: Map<Slug, Material>,
    ) = ProductListDto.AppliedFilters(
        q = raw.q?.trim()?.ifEmpty { null },
        categories = filter.categories.mapNotNull { categories[it] }.map { ProductCardDto.CategoryRef(it.id.value, it.slug.value, it.name.value) },
        colors = filter.colors.mapNotNull { palette[it] }.map { ProductListDto.Swatch(it.key.value, it.name.value, it.hex.value) },
        minPrice = filter.price.min?.toman,
        maxPrice = filter.price.max?.toman,
        onSale = filter.onSale,
        inStock = filter.inStock,
        isNew = filter.newAt != null,
        hasVideo = filter.hasVideo,
        bagTypes = filter.bagTypes.toList(),
        materials = filter.materials.mapNotNull { materials[it] }.map { ProductListDto.MaterialRef(it.slug.value, it.name.value) },
        occasions = filter.occasions.toList(),
    )

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, causes = causes) {
        /** One or more parameters are wrong; each cause names its parameter (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
