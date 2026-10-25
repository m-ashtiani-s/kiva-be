package com.kiva.application.usecase.review

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.dto.ReviewDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.repository.ReviewVoteRepository

/**
 * Reviews as one viewer sees them (the contract's Review; roadmap section 13.2, decision 19), built the same way for the
 * product's list, «نظرات من», a review just written and the panel (Rule 15). A whole page in a few reads, never one per
 * review: the media, the viewer's votes, the products (for «نظرات من» and the panel), the palette. Runs inside the caller's
 * transaction.
 */
class Reviews(
    private val mediaRepository: MediaRepository,
    private val reviewVoteRepository: ReviewVoteRepository,
    private val productQmRepository: ProductQmRepository,
    private val colorRepository: ColorRepository,
) {

    /**
     * Who looks: [viewer] (null for a guest), the panel ([panel]: every reason and reply), and whether each review names
     * its product ([withProduct], «نظرات من» and the panel).
     */
    data class Viewer(val viewer: UserId?, val panel: Boolean = false, val withProduct: Boolean = false)

    fun of(reviews: List<Review>, who: Viewer): List<ReviewDto> {
        if (reviews.isEmpty()) return emptyList()
        val mediaIds = reviews.flatMap { it.mediaIds }.distinct()
        val media = if (mediaIds.isEmpty()) emptyMap() else mediaRepository.findAll(mediaIds).associateBy { it.id }
        val voted = who.viewer?.let { reviewVoteRepository.votedBy(it, reviews.map(Review::id)) }.orEmpty()
        val products = if (who.withProduct) productQmRepository.findBriefs(reviews.map { it.productId }.distinct()) else emptyMap()
        val palette = if (reviews.any { it.purchase != null }) colorRepository.findAll().associateBy { it.key } else emptyMap()
        return reviews.map { review ->
            val own = who.viewer == review.userId
            ReviewDto(
                id = review.id.value,
                productId = review.productId.value,
                product = products[review.productId],
                authorName = review.authorName?.value,
                rating = review.rating.value,
                text = review.text.value,
                createdOn = review.createdOn,
                status = review.status,
                rejectionReason = review.rejectionReason?.value?.takeIf { own || who.panel },
                isVerifiedBuyer = review.isVerifiedBuyer,
                purchasedColor = review.purchase?.let { purchase ->
                    val inPalette = palette[purchase.colorKey]
                    OrderDetailDto.Color(purchase.colorKey.value, purchase.colorName, purchase.colorHex, inPalette?.isLight ?: false, inPalette?.sortOrder ?: Int.MAX_VALUE)
                },
                reply = review.reply?.takeIf { review.status == ReviewStatus.APPROVED || who.panel }?.let { ReviewDto.Reply(it.text.value, it.repliedOn) },
                helpfulCount = review.helpfulCount,
                myHelpfulVote = if (review.id in voted) true else null,
                // An attached upload is never removed: one missing would be the clean-up's bug, shown as nothing.
                media = review.mediaIds.mapNotNull { media[it] }.map(UploadedMediaDto::from),
                isMine = own,
            )
        }
    }

    fun of(review: Review, who: Viewer): ReviewDto = of(listOf(review), who).single()
}
