package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.ReviewDto
import com.kiva.application.usecase.review.Reviews
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/** One review as the panel sees it (its reason and reply too): what moderation and the reply answer with (Rule 6). */
class GetAdminReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val reviews: Reviews,
) : QueryUseCase<GetAdminReviewUc.Qry, ReviewDto, GetAdminReviewUc.Failure> {

    private val logger = getLogger()

    /** @property reviewId the path parameter as it came. */
    data class Qry(val reviewId: String)

    override suspend fun execute(query: Qry): Result<ReviewDto, Failure> {
        val id = ReviewId.makeNew(query.reviewId).getOrNull()
        logger.info("GetAdminReviewUc.execute reviewId=${id?.value ?: "(not an id)"}")
        if (id == null) return Result.failure(Failure.ReviewNotFound)
        return transactionManager.inTransaction {
            val review = reviewRepository.find(id) ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            Result.success(reviews.of(review, Reviews.Viewer(viewer = null, panel = true)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
