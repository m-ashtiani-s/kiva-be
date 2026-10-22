package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.RatingSummaryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.review.repository.ProductRatingRepository

/**
 * A product's rating alone (`GET /products/{productId}/reviews/summary`): the same summary as the list's. A product not
 * in the shop (or an id that can't be one) is 404 PRODUCT_NOT_FOUND. Not cached: every approval changes it.
 */
class GetReviewSummaryUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val productRatingRepository: ProductRatingRepository,
) : QueryUseCase<GetReviewSummaryUc.Qry, RatingSummaryDto, GetReviewSummaryUc.Failure> {

    private val logger = getLogger()

    data class Qry(val productId: String)

    override suspend fun execute(query: Qry): Result<RatingSummaryDto, Failure> {
        val productId = query.productId.trim().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
        logger.info("GetReviewSummaryUc.execute productId=${productId?.value ?: "(not an id)"}")
        if (productId == null) return Result.failure(Failure.ProductNotFound)
        return transactionManager.inTransaction {
            if (productQmRepository.findBriefs(listOf(productId))[productId]?.status != ProductStatus.ACTIVE) {
                return@inTransaction Result.failure(Failure.ProductNotFound)
            }
            Result.success(RatingSummaryDto.from(productRatingRepository.find(productId)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
