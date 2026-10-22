package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.RatingSummaryDto
import com.kiva.application.dto.ReviewDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.review.Reviews
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.review.repository.ProductRatingRepository
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/**
 * A product's public reviews (`GET /products/{productId}/reviews`; roadmap section 13.6, decision 18): only the approved
 * ones, filtered and in the pageable's order (ReviewSorts), with the summary over all of them whatever the filters. A
 * logged-in customer also sees their own PENDING reviews of it first, on page 1 only, outside the filters and the
 * count. A product not in the shop (or an id that can't be one) is 404 PRODUCT_NOT_FOUND; a filter that isn't one 400.
 */
class ListProductReviewsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val reviewRepository: ReviewRepository,
    private val productRatingRepository: ProductRatingRepository,
    private val reviews: Reviews,
) : QueryUseCase<ListProductReviewsUc.Qry, ListProductReviewsUc.ProductReviews, ListProductReviewsUc.Failure> {

    private val logger = getLogger()

    /** The path and the query parameters as they came; [viewer] null for a guest. */
    data class Qry(
        val viewer: UserId?,
        val productId: String,
        val pageable: Pageable,
        val rating: String? = null,
        val withMedia: String? = null,
        val verifiedOnly: String? = null,
    )

    /** The page (the customer's own pending ones first on page 1, beyond [page]'s count) and the summary. */
    data class ProductReviews(val summary: RatingSummaryDto, val page: Page<ReviewDto>)

    override suspend fun execute(query: Qry): Result<ProductReviews, Failure> {
        val productId = query.productId.trim().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
        logger.info("ListProductReviewsUc.execute productId=${productId?.value ?: "(not an id)"} page=${query.pageable.page} sort=${query.pageable.sort}")
        val filter = when (val parsed = filterOf(query)) {
            is Result.Success -> parsed.value
            is Result.Failure -> return Result.failure(parsed.failure)
        }
        if (productId == null) return Result.failure(Failure.ProductNotFound)

        return transactionManager.inTransaction {
            val product = productQmRepository.findBriefs(listOf(productId))[productId]
            if (product?.status != ProductStatus.ACTIVE) return@inTransaction Result.failure(Failure.ProductNotFound)
            val page = reviewRepository.pagePublic(productId, filter, query.pageable)
            val own = if (query.viewer != null && query.pageable.page == 1) reviewRepository.pendingOf(query.viewer, productId) else emptyList()
            val items = reviews.of(own + page.items, Reviews.Viewer(query.viewer))
            Result.success(
                ProductReviews(RatingSummaryDto.from(productRatingRepository.find(productId)), Page(items, page.page, page.size, page.totalItems)),
            )
        }
    }

    private fun filterOf(query: Qry): Result<ReviewRepository.PublicFilter, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val rating = query.rating?.trim()?.ifEmpty { null }?.let { raw ->
            raw.toIntOrNull()?.let { StarRating.makeNew(it).getOrNull() } ?: null.also { problems += StarRating.Failure.Invalid }
        }
        val withMedia = booleanOf(query.withMedia, "withMedia", problems)
        val verifiedOnly = booleanOf(query.verifiedOnly, "verifiedOnly", problems)
        return if (problems.isEmpty()) Result.success(ReviewRepository.PublicFilter(rating, withMedia, verifiedOnly)) else Result.failure(Failure.InvalidInput(problems))
    }

    private fun booleanOf(raw: String?, parameter: String, problems: MutableList<ResultFailure>): Boolean = when (raw?.trim()?.ifEmpty { null }) {
        null, "false" -> false
        "true" -> true
        else -> false.also { problems += FieldFailure(parameter, Problem.BooleanInvalid) }
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object BooleanInvalid : Problem("BOOLEAN_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
