package com.kiva.application.usecase.review.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import com.kiva.domain.aggregate.review.repository.ReviewVoteRepository
import java.time.Clock

/**
 * «این نظر مفید بود» (`PUT /reviews/{reviewId}/helpful`; roadmap section 13.2, decision 20): `true` votes, `false` takes
 * the vote back; again changes nothing. Under the review's row lock (lock 11), so votes at once are counted one after the
 * other: only an approved review (anything else, or none: 404 REVIEW_NOT_FOUND), never one's own (422 OWN_REVIEW_VOTE).
 * Answers how many found it helpful now and the voter's vote (true or null).
 */
class VoteReviewHelpfulUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val reviewVoteRepository: ReviewVoteRepository,
    private val clock: Clock,
) : CommandUseCase<VoteReviewHelpfulUc.Cmd, VoteReviewHelpfulUc.Vote, VoteReviewHelpfulUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val userId: UserId, val reviewId: String, val helpful: Boolean?)

    /** The review's count of helpful votes now, and the voter's: true, or null when they have none. */
    data class Vote(val helpfulCount: Int, val myVote: Boolean?)

    override suspend fun execute(command: Cmd): Result<Vote, Failure> {
        val id = ReviewId.makeNew(command.reviewId).getOrNull()
        logger.info("VoteReviewHelpfulUc.execute userId=${command.userId.value} reviewId=${id?.value ?: "(not an id)"} helpful=${command.helpful}")
        val helpful = command.helpful ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("helpful", FieldRequired))))
        if (id == null) return Result.failure(Failure.ReviewNotFound)

        return transactionManager.inTransaction {
            val review = reviewRepository.lock(id)?.takeIf { it.status == ReviewStatus.APPROVED } ?: return@inTransaction Result.failure(Failure.ReviewNotFound)
            if (review.userId == command.userId) return@inTransaction Result.failure(Failure.OwnReview)
            val voted = reviewVoteRepository.exists(id, command.userId)
            when {
                helpful && !voted -> {
                    reviewVoteRepository.add(id, command.userId, clock.instant())
                    review.votedHelpful()
                    reviewRepository.save(review)
                }
                !helpful && voted -> {
                    reviewVoteRepository.remove(id, command.userId)
                    review.voteTakenBack()
                    reviewRepository.save(review)
                }
            }
            Result.success(Vote(review.helpfulCount, if (helpful) true else null))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReviewNotFound : Failure("REVIEW_NOT_FOUND", FailureKind.NOT_FOUND)

        /** A vote for one's own review (422). */
        data object OwnReview : Failure("OWN_REVIEW_VOTE", FailureKind.BUSINESS_RULE)
    }
}
