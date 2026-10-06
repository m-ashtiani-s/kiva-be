package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.ProductFacetsDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductFilterQuery
import com.kiva.application.usecase.product.ProductFilters
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import java.time.Clock

/**
 * The counts beside the shop's filters (`GET /products/facets`, and `GET /products?includeFacets=true`): for each
 * category, color, material and bag form, how many products it would give among the other filters of the request,
 * and the current price range.
 *
 * Each filter is counted without its own value (disjunctive faceting, as the contract asks): with lilac picked, black
 * still says how many black bags there are, so a shopper can add it. Takes the same filters as the list, checked the
 * same way (ProductFilters).
 */
class GetProductFacetsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val materialRepository: MaterialRepository,
    private val clock: Clock,
) : QueryUseCase<GetProductFacetsUc.Qry, ProductFacetsDto, GetProductFacetsUc.Failure> {

    private val logger = getLogger()

    data class Qry(val filters: ProductFilterQuery = ProductFilterQuery())

    override suspend fun execute(query: Qry): Result<ProductFacetsDto, Failure> {
        logger.info("GetProductFacetsUc.execute q=${query.filters.q} category=${query.filters.categories} color=${query.filters.colors}")

        val now = clock.instant()
        val filter = ProductFilters.parse(query.filters, now).unwrapOr { return Result.failure(Failure.InvalidInput(it.problems)) }

        return transactionManager.inTransaction {
            val counts = productQmRepository.facets(filter, now)

            Result.success(
                ProductFacetsDto(
                    // Menu order, palette order, list order: the order the filter panel shows them in.
                    categories = categoryRepository.findAll().map {
                        ProductFacetsDto.Option(it.slug.value, it.name.value, counts.categories[it.slug] ?: 0, it.slug in filter.categories)
                    },
                    colors = colorRepository.findAll().map {
                        ProductFacetsDto.Option(it.key.value, it.name.value, counts.colors[it.key] ?: 0, it.key in filter.colors, it.hex.value)
                    },
                    materials = materialRepository.findAll().map {
                        ProductFacetsDto.Option(it.slug.value, it.name.value, counts.materials[it.slug] ?: 0, it.slug in filter.materials)
                    },
                    bagTypes = BagType.entries.map {
                        ProductFacetsDto.BagTypeOption(it, counts.bagTypes[it] ?: 0, it in filter.bagTypes)
                    },
                    price = priceRangeOf(counts.minPrice, counts.maxPrice),
                    onSaleCount = counts.onSale,
                    inStockCount = counts.inStock,
                    newCount = counts.new,
                    withVideoCount = counts.withVideo,
                ),
            )
        }
    }

    /** The contract's price slider: the cheapest rounded down and the dearest rounded up, to [PRICE_STEP]. */
    private fun priceRangeOf(min: Money?, max: Money?): ProductFacetsDto.PriceRange? {
        if (min == null || max == null) return null
        val low = min.toman / PRICE_STEP * PRICE_STEP
        val high = (max.toman + PRICE_STEP - 1) / PRICE_STEP * PRICE_STEP
        return ProductFacetsDto.PriceRange(low, high, PRICE_STEP)
    }

    companion object {
        /** From the contract: 10,000 toman. */
        const val PRICE_STEP = 10_000L
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, causes = causes) {
        /** One or more parameters are wrong; each cause names its parameter (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
