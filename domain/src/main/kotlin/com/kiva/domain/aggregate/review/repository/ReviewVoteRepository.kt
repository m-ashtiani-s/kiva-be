package com.kiva.domain.aggregate.review.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import java.time.Instant

/** Who found which review helpful (`reviewvote_tbl`): one vote per user and review. Inside the caller's transaction. */
interface ReviewVoteRepository {

    fun exists(reviewId: ReviewId, userId: UserId): Boolean

    fun add(reviewId: ReviewId, userId: UserId, now: Instant)

    fun remove(reviewId: ReviewId, userId: UserId)

    /** Of [reviewIds], the ones [userId] found helpful: a whole page in one read. */
    fun votedBy(userId: UserId, reviewIds: Collection<ReviewId>): Set<ReviewId>
}
