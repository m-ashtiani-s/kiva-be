package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.ReviewDto
import com.kiva.application.usecase.review.Reviews
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/**
 * «نظرات من» (`GET /me/reviews`; roadmap section 13.2, decision 21): every review of the customer's, in any status,
 * newest first, each naming its product (an archived one too), and how many there are in each status.
 */
class ListMyReviewsUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val reviews: Reviews,
) : QueryUseCase<ListMyReviewsUc.Qry, ListMyReviewsUc.MyReviews, Nothing> {

    private val logger = getLogger()

    data class Qry(val userId: UserId, val pageable: Pageable)

    /** The page, and the counts over all of the customer's reviews. */
    data class MyReviews(val page: Page<ReviewDto>, val total: Int, val pending: Int, val approved: Int, val rejected: Int)

    override suspend fun execute(query: Qry): Result<MyReviews, Nothing> {
        logger.info("ListMyReviewsUc.execute userId=${query.userId.value} page=${query.pageable.page}")
        return transactionManager.inTransaction {
            val page = reviewRepository.pageOfUser(query.userId, query.pageable)
            val counts = reviewRepository.countsOfUser(query.userId)
            val items = reviews.of(page.items, Reviews.Viewer(query.userId, withProduct = true))
            Result.success(
                MyReviews(
                    Page(items, page.page, page.size, page.totalItems),
                    total = counts.values.sum(),
                    pending = counts[ReviewStatus.PENDING] ?: 0,
                    approved = counts[ReviewStatus.APPROVED] ?: 0,
                    rejected = counts[ReviewStatus.REJECTED] ?: 0,
                ),
            )
        }
    }
}
