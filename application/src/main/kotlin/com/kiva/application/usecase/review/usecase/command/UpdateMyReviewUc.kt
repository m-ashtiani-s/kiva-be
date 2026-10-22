package com.kiva.application.usecase.review.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.patch.Patch
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import java.time.Clock

/**
 * The customer changes their review (`PATCH /me/reviews/{reviewId}`; roadmap section 13.2, decision 13): each field
 * that came replaces its value, the rest stay; `{}` changes nothing. The shape first (400, every problem at once).
 * Then, under the review's row lock (lock 11): their own (someone else's or none: 404 REVIEW_NOT_FOUND), not APPROVED
 * (422 REVIEW_NOT_EDITABLE); a REJECTED one goes back to the queue, unless they have another live review of the product
 * now (409 REVIEW_ALREADY_EXISTS; the unique index answers the same at the same moment). The product's rating never
 * changes: neither PENDING nor REJECTED is in it.
 */
class UpdateMyReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val clock: Clock,
) : CommandUseCase<UpdateMyReviewUc.Cmd, ReviewId, UpdateMyReviewUc.Failure> {

    private val logger = getLogger()

    /** The body as it came: a field not sent is [Patch.Unchanged]. Personal fields never go into a log ([toString]). */
    data class Cmd(val userId: UserId, val reviewId: String, val rating: Patch<Int?>, val authorName: Patch<String?>, val text: Patch<String?>) {
        override fun toString(): String =
            "Cmd(userId=${userId.value}, reviewId=$reviewId, rating=$rating, authorName=${authorName != Patch.Unchanged}, text=${text != Patch.Unchanged})"
    }

    private data class Input(val rating: Patch<StarRating>, val authorName: Patch<ReviewAuthorName?>, val text: Patch<ReviewText>)

    override suspend fun execute(command: Cmd): Result<ReviewId, Failure> {
        val id = ReviewId.makeNew(command.reviewId).getOrNull()
        logger.info("UpdateMyReviewUc.execute userId=${command.userId.value} reviewId=${id?.value ?: "(not an id)"}")
        val input = when (val checked = inputOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (id == null) return Result.failure(Failure.ReviewNotFound)

        return transactionManager.inTransaction {
            val review = reviewRepository.lock(id)?.takeIf { it.userId == command.userId } ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            val wasRejected = review.status == ReviewStatus.REJECTED
            val changed = when (val edited = review.edit(input.rating, input.authorName, input.text, clock.instant())) {
                is Result.Success -> edited.value
                is Result.Failure -> return@inTransaction Result.failure(Failure.NotEditable)
            }
            if (!changed) return@inTransaction Result.success(id)
            if (wasRejected && reviewRepository.liveOf(command.userId, review.productId) != null) return@inTransaction Result.failure(Failure.AlreadyExists)
            try {
                reviewRepository.save(review)
            } catch (e: UniqueTakenException) {
                logger.info("UpdateMyReviewUc: ${e.message}")
                return@inTransaction Result.failure(Failure.AlreadyExists)
            }
            logger.info("UpdateMyReviewUc: review ${id.value} changed, now ${review.status}")
            Result.success(id)
        }
    }

    /** The fields that came, each checked, every problem at once. */
    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val rating: Patch<StarRating> = when (val sent = command.rating) {
            Patch.Unchanged -> Patch.Unchanged
            is Patch.Set -> when (val raw = sent.value) {
                null -> Patch.Unchanged.also { problems += FieldFailure("rating", FieldRequired) }
                else -> when (val made = StarRating.makeNew(raw)) {
                    is Result.Success -> Patch.Set(made.value)
                    is Result.Failure -> Patch.Unchanged.also { problems += made.failure }
                }
            }
        }
        val authorName: Patch<ReviewAuthorName?> = when (val sent = command.authorName) {
            Patch.Unchanged -> Patch.Unchanged
            is Patch.Set -> when (val made = ReviewAuthorName.makeNew(sent.value).forField("authorName")) {
                is Result.Success -> Patch.Set(made.value)
                is Result.Failure -> Patch.Unchanged.also { problems += made.failure }
            }
        }
        val text: Patch<ReviewText> = when (val sent = command.text) {
            Patch.Unchanged -> Patch.Unchanged
            is Patch.Set -> when (val made = ReviewText.makeNew(sent.value, ReviewText.MIN_REVIEW, ReviewText.MAX_LENGTH, FieldRequired).forField("text")) {
                is Result.Success -> Patch.Set(made.value)
                is Result.Failure -> Patch.Unchanged.also { problems += made.failure }
            }
        }
        return if (problems.isEmpty()) Result.success(Input(rating, authorName, text)) else Result.failure(Failure.InvalidInput(problems))
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Approved already: delete it and write it again (422). */
        data object NotEditable : Failure(Review.NotEditable.code, Review.NotEditable.kind)

        /** Sent back to the queue while another live review of the product is theirs (409). */
        data object AlreadyExists : Failure("REVIEW_ALREADY_EXISTS", FailureKind.CONFLICT)
    }
}
