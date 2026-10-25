package com.kiva.domain.aggregate.review.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
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

    /** «نظرات من»: [userId]'s reviews in any status, newest first (ties to the newer id), for reading only. */
    fun pageOfUser(userId: UserId, pageable: Pageable): Page<Review>

    /**
     * A product's public reviews: only APPROVED ones, [filter]ed, in the pageable's order (newest, highest, lowest,
     * helpful; the newer review breaks every tie), for reading only.
     */
    fun pagePublic(productId: ProductId, filter: PublicFilter, pageable: Pageable): Page<Review>

    /** [userId]'s PENDING reviews of [productId], newest first: shown to them first on the product's page 1. */
    fun pendingOf(userId: UserId, productId: ProductId): List<Review>

    /** The panel's queue: every review, [filter]ed, newest written first (ties to the newer id), for reading only. */
    fun pageAdmin(filter: AdminFilter, pageable: Pageable): Page<Review>

    /** How many reviews wait for moderation, whatever the panel's filters. */
    fun countPending(): Int

    /** How many reviews [userId] has in each status; a status with none is missing. */
    fun countsOfUser(userId: UserId): Map<ReviewStatus, Int>

    /** The products [userId] has a live review of: «نظر بده» and «منتظر نظر تو». */
    fun liveProductIdsOf(userId: UserId): Set<ProductId>

    /**
     * Writes the review's change, only if its row still has the version it was read with, and raises that version.
     * Throws ConcurrentChangeException when someone saved it in the meantime, UniqueTakenException when it went live
     * again while another of its author and product is.
     */
    /**
     * Makes every review of [userId] anonymous, for deleting the account (roadmap phase 14, AccountEraser): no author name
     * («کاربر کیوا» is shown) and no contact. The text, the rating and the photos stay, and the review stays where it
     * is (approved ones on the product's page). How many there were.
     */
    fun anonymizeAllOf(userId: UserId): Int

    fun save(review: Review)

    /** The public list's filters: [rating] stars only, only with photos, only by verified buyers. */
    data class PublicFilter(val rating: StarRating? = null, val withMedia: Boolean = false, val verifiedOnly: Boolean = false)

    /** The panel's filters: one [status], one [productId], with or without Kiva's reply ([hasReply]); null is any. */
    data class AdminFilter(val status: ReviewStatus? = null, val productId: ProductId? = null, val hasReply: Boolean? = null)
}

/** Hands out the ids of new reviews, each only once, from the `reviewid_seq` database sequence. */
interface ReviewIdGenerator {
    fun nextReviewId(): ReviewId
}
