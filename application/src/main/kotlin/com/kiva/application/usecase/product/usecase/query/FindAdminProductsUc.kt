package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.AdminProductListItemDto
import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.repository.AdminProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.railway.zipAll
import com.kiva.common.text.normalizePersian
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus

/**
 * The panel's product list (`GET /admin/products`; ADMIN, CATALOG_MANAGER): every status, the exact stock.
 *
 * Filters, every mistake at once (400): `q` (at most 100, through normalizePersian; text, a code's start or an exact
 * barcode), `status`, `category` (slugs), `stockStatus`. The page is read with a fixed number of queries (rows, count,
 * the first media of each default color) plus the categories' names, never one per product.
 */
class FindAdminProductsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
) : QueryUseCase<FindAdminProductsUc.Qry, Page<AdminProductListItemDto>, FindAdminProductsUc.Failure> {

    private val logger = getLogger()

    /** The query parameters as they arrived; [categories] already split at their commas. */
    data class Qry(
        val pageable: Pageable,
        val q: String? = null,
        val status: String? = null,
        val categories: List<String> = emptyList(),
        val stockStatus: String? = null,
    )

    override suspend fun execute(query: Qry): Result<Page<AdminProductListItemDto>, Failure> {
        logger.info("FindAdminProductsUc.execute page=${query.pageable.page} sort=${query.pageable.sort} status=${query.status} stock=${query.stockStatus}")
        val filter = filterOf(query).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val page = productQmRepository.findForAdmin(filter, query.pageable)
            // A handful of categories: read them all once rather than one per product.
            val categories = categoryRepository.findAll().associateBy { it.slug }
            Result.success(
                page.map { row ->
                    val category = categories[row.categorySlug] ?: error("No category ${row.categorySlug.value}")
                    AdminProductListItemDto(
                        id = row.productId.value,
                        sku = row.sku.value,
                        name = row.name.value,
                        category = ProductCardDto.CategoryRef(category.id.value, category.slug.value, category.name.value),
                        status = row.status,
                        price = PriceDto.from(row.priceTag),
                        totalStock = row.availableTotal,
                        variantsCount = row.variantsCount,
                        lowStockVariants = row.lowStockVariants,
                        soldCount = row.soldCount,
                        updatedAt = row.updatedOn,
                        image = row.defaultMedia,
                    )
                },
            )
        }
    }

    private fun filterOf(query: Qry): Result<AdminProductFilter, InputProblems> {
        val text = query.q?.trim()?.ifEmpty { null }
        return Result.zip(
            if ((text?.length ?: 0) > MAX_QUERY_LENGTH) Result.failure(Problem.SearchTooLong) else Result.success(text?.normalizePersian()),
            query.status?.trim()?.ifEmpty { null }?.let { ProductStatus.makeNew(it) } ?: Result.success(null),
            Result.zipAll(query.categories.map { Slug.makeNew(it.trim()).forField("category") }, ::InputProblems),
            query.stockStatus?.trim()?.ifEmpty { null }?.let { raw ->
                StockStatus.entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Problem.StockStatusInvalid)
            } ?: Result.success(null),
            ::InputProblems,
        ) { q, status, categories, stockStatus ->
            AdminProductFilter(q, status, categories.toSet(), stockStatus, StockPolicy.LOW_STOCK_THRESHOLD)
        }
    }

    sealed class Problem(code: String, field: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object SearchTooLong : Problem("SEARCH_QUERY_TOO_LONG", "q", mapOf("maxLength" to MAX_QUERY_LENGTH))
        data object StockStatusInvalid : Problem("STOCK_STATUS_INVALID", "stockStatus")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** One or more parameters are wrong; each cause names its parameter (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 100
    }
}
