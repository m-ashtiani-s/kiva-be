package com.kiva.application.usecase.review.usecase.command

import com.kiva.application.usecase.review.RatingBook
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import java.time.Clock

/**
 * The customer deletes their review (`DELETE /me/reviews/{reviewId}`; roadmap section 13.2, decision 14), in any status:
 * under its row lock (lock 11), their own (someone else's or none: 404 REVIEW_NOT_FOUND); an approved one leaves its
 * product's rating first (RatingBook, the rating's row right after); then the row and its votes go. Its uploads stay
 * (an attached upload is never removed).
 */
class DeleteMyReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val ratingBook: RatingBook,
    private val clock: Clock,
) : CommandUseCase<DeleteMyReviewUc.Cmd, Unit, DeleteMyReviewUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val userId: UserId, val reviewId: String)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReviewId.makeNew(command.reviewId).getOrNull()
        logger.info("DeleteMyReviewUc.execute userId=${command.userId.value} reviewId=${id?.value ?: "(not an id)"}")
        if (id == null) return Result.failure(Failure.ReviewNotFound)
        return transactionManager.inTransaction {
            val review = reviewRepository.lock(id)?.takeIf { it.userId == command.userId } ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            ratingBook.deleted(review, clock.instant())
            reviewRepository.delete(id)
            logger.info("DeleteMyReviewUc: review ${id.value} of product ${review.productId.value} deleted, it was ${review.status}")
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
