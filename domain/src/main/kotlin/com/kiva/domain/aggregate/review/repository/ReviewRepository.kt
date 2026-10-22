package com.kiva.domain.aggregate.review.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import java.time.Instant

/**
 * Reviews, kept as rows. A change takes the review's row lock first ([lock]; roadmap section 4.11, lock 11), then, when
 * the product's rating changes, its rating's row (ProductRatingRepository.lock); [save] checks the row's version too,
 * the last wall. At most one live review (PENDING or APPROVED) per user and product: ReviewDomainService checks first,
 * and a partial unique index is the wall behind it ([add] then throws UniqueTakenException).
 */
interface ReviewRepository {

    /** Stores a new review. Throws UniqueTakenException when another live one of its author and product won the race. */
    fun add(review: Review)

    /** The review [id] for reading only; null when there is none. */
    fun find(id: ReviewId): Review?

    /** Locks the review's row and reads it; null when there is none. */
    fun lock(id: ReviewId): Review?

    /** Removes the review and its votes. */
    fun delete(id: ReviewId)

    /** [userId]'s live review of [productId] (PENDING or APPROVED), for reading only; null when none. */
    fun liveOf(userId: UserId, productId: ProductId): Review?

    /** When each review [userId] wrote after [since] was written: the daily cap's count. */
    fun createdSince(userId: UserId, since: Instant): List<Instant>

    /** The products [userId] has a live review of: «نظر بده» and «منتظر نظر تو». */
    fun liveProductIdsOf(userId: UserId): Set<ProductId>

    /**
     * Writes the review's change, only if its row still has the version it was read with, and raises that version.
     * Throws ConcurrentChangeException when someone saved it in the meantime, UniqueTakenException when it went live
     * again while another of its author and product is.
     */
    fun save(review: Review)
}

/** Hands out the ids of new reviews, each only once, from the `reviewid_seq` database sequence. */
interface ReviewIdGenerator {
    fun nextReviewId(): ReviewId
}
