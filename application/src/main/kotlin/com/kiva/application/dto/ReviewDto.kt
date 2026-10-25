package com.kiva.application.dto

import com.kiva.application.repository.ProductBriefQm
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import java.time.Instant

/**
 * A review as one viewer sees it (the contract's Review; roadmap section 13.2, decision 19). Without the Persian words:
 * the status's label, «کاربر کیوا» and «کیوا» are the HTTP layer's (ReviewTexts).
 *
 * Personal: the text, the author's name, the reason and the reply never go into a log ([toString]).
 *
 * @property product only in «نظرات من».
 * @property authorName null when the author gave none.
 * @property rejectionReason only for its author (and the panel).
 * @property reply only while APPROVED (the panel sees it always).
 * @property myHelpfulVote true when the viewer found it helpful; null otherwise, and for a guest.
 */
data class ReviewDto(
    val id: Long,
    val productId: Long,
    val product: ProductBriefQm?,
    val authorName: String?,
    val rating: Int,
    val text: String,
    val createdOn: Instant,
    val status: ReviewStatus,
    val rejectionReason: String?,
    val isVerifiedBuyer: Boolean,
    val purchasedColor: OrderDetailDto.Color?,
    val reply: Reply?,
    val helpfulCount: Int,
    val myHelpfulVote: Boolean?,
    val media: List<UploadedMediaDto>,
    val isMine: Boolean,
) {
    override fun toString(): String = "ReviewDto(id=$id, productId=$productId, status=$status)"

    /** Kiva's reply: its author is always «کیوا»; [repliedOn] its last save. */
    data class Reply(val text: String, val repliedOn: Instant) {
        override fun toString(): String = "Reply(repliedOn=$repliedOn)"
    }
}

/** A review in the panel's queue: the review, its author's account, and how to reach them (theirs, or the account's phone). */
data class AdminReviewDto(val review: ReviewDto, val customerId: Long, val contact: String?) {
    override fun toString(): String = "AdminReviewDto(id=${review.id})"
}
