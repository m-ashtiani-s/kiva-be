package com.kiva.domain.aggregate.review.entity

import com.kiva.common.patch.Patch
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewContact
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewPurchase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewReply
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus.APPROVED
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus.PENDING
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus.REJECTED
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import java.time.Instant

/**
 * A customer's review of a product (roadmap section 13): stars, a text, maybe photos, maybe linked to a delivered line
 * of their own order (the badge «خریدار»). Kept as a row (decision 11 of section 1): its history isn't worth keeping.
 *
 * ```
 * PENDING ─┬─→ APPROVED ⇄ REJECTED
 *          └─→ REJECTED
 * ```
 *
 * Rules it keeps by itself:
 * - a new review is PENDING; support approves or turns it down, and may change its mind (decision 10); the same status
 *   again is [InvalidTransition] (422);
 * - it is turned down only with a reason (only its author sees it);
 * - its author edits it only while PENDING or REJECTED (an APPROVED one is [NotEditable]); editing a REJECTED one sends
 *   it back to the queue, its reason gone (decision 13);
 * - Kiva replies only under an APPROVED review (decision 12); turning it down keeps the reply, unseen;
 * - only an APPROVED review counts in its product's rating ([countsInRating]).
 *
 * What it can't know: whether its product is published, whether its author has another live review of it (one at a time,
 * decision 2: ReviewDomainService and a partial unique index), its votes (ReviewVoteRepository), its product's rating
 * (RatingBook).
 *
 * @property version the row's, for the repository's check that nobody saved it in the meantime.
 */
class Review private constructor(
    val id: ReviewId,
    val productId: ProductId,
    val userId: UserId,
    rating: StarRating,
    authorName: ReviewAuthorName?,
    val contact: ReviewContact?,
    text: ReviewText,
    status: ReviewStatus,
    rejectionReason: ReviewText?,
    val purchase: ReviewPurchase?,
    val mediaIds: List<MediaId>,
    helpfulCount: Int,
    reply: ReviewReply?,
    val createdOn: Instant,
    updatedOn: Instant,
    moderatedOn: Instant?,
    moderatedBy: UserId?,
    val version: Long,
) {
    var rating: StarRating = rating
        private set
    var authorName: ReviewAuthorName? = authorName
        private set
    var text: ReviewText = text
        private set
    var status: ReviewStatus = status
        private set
    var rejectionReason: ReviewText? = rejectionReason
        private set
    var helpfulCount: Int = helpfulCount
        private set
    var reply: ReviewReply? = reply
        private set
    var updatedOn: Instant = updatedOn
        private set
    var moderatedOn: Instant? = moderatedOn
        private set
    var moderatedBy: UserId? = moderatedBy
        private set

    init {
        require(mediaIds.size <= MAX_MEDIA && mediaIds.distinct().size == mediaIds.size) { "Review ${id.value}: media ${mediaIds.size}" }
        require((status == REJECTED) == (rejectionReason != null)) { "Review ${id.value} is $status with a rejection reason ${rejectionReason != null}" }
        require(status == PENDING || moderatedOn != null) { "Review ${id.value} is $status without a moderation" }
        require(helpfulCount >= 0) { "Review ${id.value} is helpful to $helpfulCount" }
    }

    /** The badge «خریدار»: it is linked to a delivered line of its author's own order. */
    val isVerifiedBuyer: Boolean get() = purchase != null

    /** Only an approved review is in its product's rating. */
    val countsInRating: Boolean get() = status == APPROVED

    /**
     * Its author changes it (`PATCH /me/reviews/{id}`): each [Patch.Unchanged] field stays. Only while PENDING or
     * REJECTED; a REJECTED one goes back to the queue, its reason gone. True when something changed.
     */
    fun edit(rating: Patch<StarRating>, authorName: Patch<ReviewAuthorName?>, text: Patch<ReviewText>, now: Instant): Result<Boolean, NotEditable> {
        if (status == APPROVED) return Result.failure(NotEditable)
        val newRating = (rating as? Patch.Set)?.value ?: this.rating
        val newName = if (authorName is Patch.Set) authorName.value else this.authorName
        val newText = (text as? Patch.Set)?.value ?: this.text
        val changed = newRating != this.rating || newName != this.authorName || newText != this.text
        if (!changed) return Result.success(false)
        this.rating = newRating
        this.authorName = newName
        this.text = newText
        if (status == REJECTED) {
            status = PENDING
            rejectionReason = null
        }
        updatedOn = now
        return Result.success(true)
    }

    /** Support put it on the site, maybe with Kiva's [reply] at once (decision 11). From PENDING or REJECTED. */
    fun approve(reply: ReviewText?, by: UserId, now: Instant): Result<Review, InvalidTransition> {
        refuseUnless(APPROVED)?.let { return Result.failure(it) }
        status = APPROVED
        rejectionReason = null
        if (reply != null) this.reply = ReviewReply(reply, now, by)
        moderated(by, now)
        return Result.success(this)
    }

    /** Support turned it down (or took it off the site), saying why: only its author reads it. From PENDING or APPROVED. */
    fun reject(reason: ReviewText, by: UserId, now: Instant): Result<Review, InvalidTransition> {
        refuseUnless(REJECTED)?.let { return Result.failure(it) }
        status = REJECTED
        rejectionReason = reason
        moderated(by, now)
        return Result.success(this)
    }

    /** Kiva's reply, written or rewritten: only under an APPROVED review. */
    fun reply(text: ReviewText, by: UserId, now: Instant): Result<Review, InvalidTransition> {
        if (status != APPROVED) return Result.failure(InvalidTransition(status, APPROVED, allowedTransitions()))
        reply = ReviewReply(text, now, by)
        updatedOn = now
        return Result.success(this)
    }

    /** Kiva's reply taken away; true when there was one. */
    fun removeReply(now: Instant): Boolean {
        if (reply == null) return false
        reply = null
        updatedOn = now
        return true
    }

    /** Someone found it helpful. */
    fun votedHelpful() {
        helpfulCount += 1
    }

    /** Someone took their vote back. */
    fun voteTakenBack() {
        check(helpfulCount > 0) { "Review ${id.value} has no vote to take back" }
        helpfulCount -= 1
    }

    /** Where support may move it now. */
    fun allowedTransitions(): Set<ReviewStatus> = when (status) {
        PENDING -> setOf(APPROVED, REJECTED)
        APPROVED -> setOf(REJECTED)
        REJECTED -> setOf(APPROVED)
    }

    private fun refuseUnless(to: ReviewStatus): InvalidTransition? {
        val allowed = allowedTransitions()
        return if (to in allowed) null else InvalidTransition(status, to, allowed)
    }

    private fun moderated(by: UserId, now: Instant) {
        moderatedOn = now
        moderatedBy = by
        updatedOn = now
    }

    override fun equals(other: Any?): Boolean = other is Review && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Review(id=${id.value}, productId=${productId.value}, status=$status)"

    companion object {
        /** At most this many photos and videos of a review (decision 8). */
        const val MAX_MEDIA = 5

        /**
         * A new review, PENDING from [now]. Whether its product is published, whether its author may write another,
         * its media and its purchase are ReviewDomainService's to check first.
         */
        fun submit(
            id: ReviewId,
            productId: ProductId,
            userId: UserId,
            rating: StarRating,
            authorName: ReviewAuthorName?,
            contact: ReviewContact?,
            text: ReviewText,
            purchase: ReviewPurchase?,
            mediaIds: List<MediaId>,
            now: Instant,
        ): Review = Review(
            id, productId, userId, rating, authorName, contact, text, PENDING, rejectionReason = null, purchase, mediaIds, helpfulCount = 0,
            reply = null, createdOn = now, updatedOn = now, moderatedOn = null, moderatedBy = null, version = 0,
        )

        fun constructFromPersisted(
            id: ReviewId,
            productId: ProductId,
            userId: UserId,
            rating: StarRating,
            authorName: ReviewAuthorName?,
            contact: ReviewContact?,
            text: ReviewText,
            status: ReviewStatus,
            rejectionReason: ReviewText?,
            purchase: ReviewPurchase?,
            mediaIds: List<MediaId>,
            helpfulCount: Int,
            reply: ReviewReply?,
            createdOn: Instant,
            updatedOn: Instant,
            moderatedOn: Instant?,
            moderatedBy: UserId?,
            version: Long,
        ): Review = Review(
            id, productId, userId, rating, authorName, contact, text, status, rejectionReason, purchase, mediaIds, helpfulCount, reply, createdOn,
            updatedOn, moderatedOn, moderatedBy, version,
        )
    }

    // ============ failures ============

    /** It can't go from [from] to [to] (422 INVALID_STATUS_TRANSITION); [allowed] is where it may go instead. */
    data class InvalidTransition(val from: ReviewStatus, val to: ReviewStatus, val allowed: Set<ReviewStatus>) : ResultFailure(
        "INVALID_STATUS_TRANSITION",
        FailureKind.BUSINESS_RULE,
        meta = mapOf("from" to from.name, "to" to to.name, "allowed" to allowed.map { it.name }.sorted()),
    )

    /** An approved review isn't edited: delete it and write it again (422). */
    data object NotEditable : ResultFailure("REVIEW_NOT_EDITABLE", FailureKind.BUSINESS_RULE)
}
