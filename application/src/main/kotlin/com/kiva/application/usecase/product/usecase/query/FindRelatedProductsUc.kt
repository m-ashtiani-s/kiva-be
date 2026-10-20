package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.ProductCardDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductCards
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * «محصولات مشابه» beside a product (`GET /products/{productId}/related`): other products in the shop, those of the same
 * category first, then the best selling, never the product itself (the repository's order, as the prototype has it).
 * Their cards are made exactly as in the list (ProductCards).
 *
 * A product not in the shop has no related products: PRODUCT_NOT_FOUND, as for its page.
 */
class FindRelatedProductsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val clock: Clock,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<FindRelatedProductsUc.Qry, List<ProductCardDto>, FindRelatedProductsUc.Failure> {

    private val logger = getLogger()

    /** The path segment and `?limit=` as they arrived. */
    data class Qry(val productId: String, val limit: String? = null)

    override suspend fun execute(query: Qry): Result<List<ProductCardDto>, Failure> {
        logger.info("FindRelatedProductsUc.execute productId=${query.productId} limit=${query.limit}")

        // Checked before the transaction: a wrong limit is the client's mistake, a non-number can name no product.
        val limit = limitOf(query.limit) ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("limit", Failure.LimitInvalid))))
        val productId = query.productId.trim().normalizeDigits().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.ProductNotFound)
        val now = clock.instant()

        return transactionManager.inTransaction {
            val related = productQmRepository.findRelated(productId, limit) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val categories = categoryRepository.findAll().associateBy { it.slug }
            val palette = colorRepository.findAll().associateBy { it.key }
            val settings = shopSettingsRepository.get()
            Result.success(related.map { ProductCards.cardOf(it, categories.getValue(it.categorySlug), palette, now, settings.freeShippingThreshold, settings.lowStockThreshold) })
        }
    }

    /** Missing or empty is the contract's default; a number outside 1..[MAX_LIMIT] or not a number is null. */
    private fun limitOf(raw: String?): Int? {
        val text = raw?.trim()?.ifEmpty { null } ?: return DEFAULT_LIMIT
        return text.normalizeDigits().toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
    }

    companion object {
        /** From the contract's `limit`. */
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 24
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** `limit` is wrong (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object LimitInvalid : Failure("LIMIT_INVALID", FailureKind.INVALID, meta = mapOf("max" to MAX_LIMIT))

        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
