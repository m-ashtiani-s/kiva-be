package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.ReviewDto
import com.kiva.application.usecase.review.Reviews
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/**
 * One of the customer's reviews as they see it: what writing or editing one answers with (Rule 6). Someone else's or
 * none is REVIEW_NOT_FOUND.
 */
class GetMyReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val reviews: Reviews,
) : QueryUseCase<GetMyReviewUc.Qry, ReviewDto, GetMyReviewUc.Failure> {

    private val logger = getLogger()

    data class Qry(val userId: UserId, val reviewId: ReviewId)

    override suspend fun execute(query: Qry): Result<ReviewDto, Failure> {
        logger.info("GetMyReviewUc.execute userId=${query.userId.value} reviewId=${query.reviewId.value}")
        return transactionManager.inTransaction {
            val review = reviewRepository.find(query.reviewId)?.takeIf { it.userId == query.userId }
                ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            Result.success(reviews.of(review, Reviews.Viewer(query.userId)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
