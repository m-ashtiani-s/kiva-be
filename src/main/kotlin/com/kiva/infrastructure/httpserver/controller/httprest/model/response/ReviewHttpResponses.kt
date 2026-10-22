package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminReviewDto
import com.kiva.application.dto.ReviewDto
import com.kiva.application.repository.ProductBriefQm
import com.kiva.application.usecase.review.usecase.query.ListMyReviewsUc
import com.kiva.application.usecase.review.usecase.query.ListProductsAwaitingMyReviewUc
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** The Persian words of a review (roadmap section 13.5): its status, the name of an author who gave none, and Kiva's. */
object ReviewTexts {

    /** An author who gave no name. */
    const val ANONYMOUS = "کاربر کیوا"

    /** The author of every reply. */
    const val KIVA = "کیوا"

    fun statusLabel(status: ReviewStatus): String = when (status) {
        ReviewStatus.PENDING -> "در انتظار تأیید"
        ReviewStatus.APPROVED -> "منتشر شده"
        ReviewStatus.REJECTED -> "رد شده"
    }
}

/**
 * Contract schema `Review`, every field always there (decision 30). The text is plain text, not HTML. Personal fields:
 * [toString] shows none of them.
 */
@Serializable
data class ReviewHttpResponse(
    val id: Long,
    val productId: Long,
    val product: ProductRefHttpResponse?,
    val authorName: String,
    val authorInitial: String,
    val rating: Int,
    val title: String?,
    val text: String,
    val createdAt: String,
    val status: String,
    val statusLabel: String,
    val rejectionReason: String?,
    val isVerifiedBuyer: Boolean,
    val purchasedColor: ColorHttpResponse?,
    val reply: ReviewReplyHttpResponse?,
    val helpfulCount: Int,
    val myHelpfulVote: Boolean?,
    val media: List<MediaAssetHttpResponse>,
    val isMine: Boolean,
) {
    override fun toString(): String = "ReviewHttpResponse(id=$id, status=$status)"

    companion object {
        fun from(review: ReviewDto, urls: MediaUrls): ReviewHttpResponse {
            val name = review.authorName ?: ReviewTexts.ANONYMOUS
            return ReviewHttpResponse(
                id = review.id,
                productId = review.productId,
                product = review.product?.let { refOf(it, urls) },
                authorName = name,
                authorInitial = name.first().toString(),
                rating = review.rating,
                title = null,
                text = review.text,
                createdAt = review.createdOn.toString(),
                status = review.status.name,
                statusLabel = ReviewTexts.statusLabel(review.status),
                rejectionReason = review.rejectionReason,
                isVerifiedBuyer = review.isVerifiedBuyer,
                purchasedColor = review.purchasedColor?.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) },
                reply = review.reply?.let { ReviewReplyHttpResponse(it.text, ReviewTexts.KIVA, it.repliedOn.toString()) },
                helpfulCount = review.helpfulCount,
                myHelpfulVote = review.myHelpfulVote,
                media = review.media.map { MediaAssetHttpResponse.from(it, urls) },
                isMine = review.isMine,
            )
        }

        fun refOf(product: ProductBriefQm, urls: MediaUrls) = ProductRefHttpResponse(
            id = product.productId.value,
            slug = product.slug.value,
            sku = product.sku.value,
            name = product.name.value,
            imageUrl = product.image?.let { MediaAssetHttpResponse.from(it, urls).thumbnailUrl },
            colorKey = null,
        )
    }
}

/** Kiva's reply under a review; its author is always «کیوا». */
@Serializable
data class ReviewReplyHttpResponse(val text: String, val authorName: String, val createdAt: String) {
    override fun toString(): String = "ReviewReplyHttpResponse(createdAt=$createdAt)"
}

/** Contract schema `MyReviewListResponse`. */
@Serializable
data class MyReviewListHttpResponse(val items: List<ReviewHttpResponse>, val meta: PageMetaHttpResponse, val counts: Counts) {
    @Serializable
    data class Counts(val total: Int, val pending: Int, val approved: Int, val rejected: Int)

    companion object {
        fun from(mine: ListMyReviewsUc.MyReviews, urls: MediaUrls) = MyReviewListHttpResponse(
            items = mine.page.items.map { ReviewHttpResponse.from(it, urls) },
            meta = mine.page.let { PageMetaHttpResponse(it.page, it.size, it.totalItems, it.totalPages, it.hasNext) },
            counts = Counts(mine.total, mine.pending, mine.approved, mine.rejected),
        )
    }
}

/** Contract schema `ReviewablePurchase`: a delivered line awaiting the customer's review. */
@Serializable
data class ReviewablePurchaseHttpResponse(
    val orderCode: String,
    val orderItemId: Int,
    val product: ProductRefHttpResponse,
    val color: ColorHttpResponse,
    val deliveredAt: String,
) {
    companion object {
        fun from(awaiting: ListProductsAwaitingMyReviewUc.Awaiting, urls: MediaUrls) = ReviewablePurchaseHttpResponse(
            orderCode = awaiting.orderCode,
            orderItemId = awaiting.lineId,
            product = ReviewHttpResponse.refOf(awaiting.product, urls),
            color = awaiting.color.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) },
            deliveredAt = awaiting.deliveredOn.toString(),
        )
    }
}

/** One row of the panel's review queue: every field of `Review` and the panel's own. */
@Serializable
data class AdminReviewHttpResponse(
    val id: Long,
    val productId: Long,
    val product: ProductRefHttpResponse?,
    val authorName: String,
    val authorInitial: String,
    val rating: Int,
    val title: String?,
    val text: String,
    val createdAt: String,
    val status: String,
    val statusLabel: String,
    val rejectionReason: String?,
    val isVerifiedBuyer: Boolean,
    val purchasedColor: ColorHttpResponse?,
    val reply: ReviewReplyHttpResponse?,
    val helpfulCount: Int,
    val myHelpfulVote: Boolean?,
    val media: List<MediaAssetHttpResponse>,
    val isMine: Boolean,
    val customerId: Long,
    val contact: String?,
) {
    override fun toString(): String = "AdminReviewHttpResponse(id=$id, status=$status)"

    companion object {
        fun from(admin: AdminReviewDto, urls: MediaUrls): AdminReviewHttpResponse {
            val r = ReviewHttpResponse.from(admin.review, urls)
            return AdminReviewHttpResponse(
                r.id, r.productId, r.product, r.authorName, r.authorInitial, r.rating, r.title, r.text, r.createdAt, r.status, r.statusLabel,
                r.rejectionReason, r.isVerifiedBuyer, r.purchasedColor, r.reply, r.helpfulCount, r.myHelpfulVote, r.media, r.isMine,
                admin.customerId, admin.contact,
            )
        }
    }
}
