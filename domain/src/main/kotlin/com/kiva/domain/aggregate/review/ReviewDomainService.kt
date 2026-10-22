package com.kiva.domain.aggregate.review

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewContact
import com.kiva.domain.aggregate.review.model.valueobject.ReviewPurchase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.review.repository.ReviewIdGenerator
import java.time.Duration
import java.time.Instant

/**
 * What a review alone can't know when it is written (roadmap section 13.2, decisions 1 to 3, 8 and 9): whether its
 * product takes reviews now, whether its author may write another, whether its photos are theirs, and which of their
 * purchases it is linked to. Hands out the new review's id and makes the review. Each rule is its own step, so the use
 * case asks them in the contract's order: [gate], [mediaProblems], [purchaseLink], then [submit].
 *
 * It never saves and never opens a transaction.
 */
class ReviewDomainService(
    private val reviewIdGenerator: ReviewIdGenerator,
) {
    private val logger = getLogger()

    /**
     * Whether [userId] may write a review of the product now: it is published ([productActive]; ProductNotFound, 404),
     * they have no live review of it ([liveExists]; AlreadyExists, 409), and fewer than [MAX_PER_DAY] in the last
     * [WINDOW] ([recent], when each of theirs was written; RateLimited, 429 with how long to wait). Null when they may.
     */
    fun gate(productActive: Boolean, liveExists: Boolean, recent: List<Instant>, now: Instant): GateFailure? {
        if (!productActive) return GateFailure.ProductNotFound
        if (liveExists) return GateFailure.AlreadyExists
        val inWindow = recent.filter { it.isAfter(now.minus(WINDOW)) }.sorted()
        if (inWindow.size < MAX_PER_DAY) return null
        // The oldest that has to leave the window for the count to drop below the cap.
        val leaving = inWindow[inWindow.size - MAX_PER_DAY]
        return GateFailure.RateLimited(Duration.between(now, leaving.plus(WINDOW)))
    }

    /**
     * What is wrong with the review's photos, each under its field (decision 8): any at all while [enabled] is off
     * (REVIEW_MEDIA_DISABLED on `mediaIds`); one that isn't a review photo uploaded by [userId] (MEDIA_NOT_FOUND on
     * `mediaIds.{i}`, the same answer as one that isn't there). [found] are the ones of [mediaIds] that exist.
     */
    fun mediaProblems(userId: UserId, mediaIds: List<MediaId>, found: List<MediaAsset>, enabled: Boolean): List<ResultFailure> {
        if (mediaIds.isEmpty()) return emptyList()
        if (!enabled) return listOf(FieldFailure("mediaIds", Problem.MediaDisabled))
        val own = found.filter { it.purpose == MediaPurpose.REVIEW_PHOTO && it.uploadedBy == userId }.map { it.id }.toSet()
        return mediaIds.mapIndexedNotNull { i, id -> FieldFailure("mediaIds.$i", Problem.MediaNotFound).takeIf { id !in own } }
    }

    /**
     * Which delivered line of the author's own orders the review of [productId] is linked to (decision 3).
     *
     * Named ([orderCode] and [orderItemId], [ordered] the lines of that order of the author's, null when they have none
     * by that code): ORDER_NOT_FOUND (404); a line it doesn't have, ORDER_ITEM_NOT_FOUND on `orderItemId` (400); a line
     * of another product, ORDER_ITEM_PRODUCT_MISMATCH (400); an order not delivered or cancelled,
     * REVIEW_PURCHASE_NOT_DELIVERED (422). Not named: the latest delivered of [candidates] (the author's delivered lines
     * of this product, never of a cancelled order), or none.
     */
    fun purchaseLink(
        productId: ProductId,
        orderCode: OrderCode?,
        orderItemId: Int?,
        ordered: List<PurchaseLine>?,
        candidates: List<PurchaseLine>,
    ): Result<ReviewPurchase?, PurchaseFailure> {
        if (orderCode == null || orderItemId == null) {
            val latest = candidates.filter { it.productId == productId && it.delivered }.maxByOrNull { checkNotNull(it.deliveredOn) }
            return Result.success(latest?.purchase)
        }
        val lines = ordered ?: return Result.failure(PurchaseFailure.OrderNotFound)
        val line = lines.firstOrNull { it.lineId == orderItemId }
            ?: return Result.failure(PurchaseFailure.InvalidInput(FieldFailure("orderItemId", Problem.LineNotFound)))
        if (line.productId != productId) return Result.failure(PurchaseFailure.InvalidInput(FieldFailure("orderItemId", Problem.ProductMismatch)))
        if (!line.delivered) return Result.failure(PurchaseFailure.NotDelivered)
        return Result.success(line.purchase)
    }

    /** The new PENDING review, its id handed out; every rule above was asked first. */
    fun submit(
        productId: ProductId,
        userId: UserId,
        rating: StarRating,
        authorName: ReviewAuthorName?,
        contact: ReviewContact?,
        text: ReviewText,
        purchase: ReviewPurchase?,
        mediaIds: List<MediaId>,
        now: Instant,
    ): Review {
        logger.info("submit productId=${productId.value} userId=${userId.value} rating=${rating.value} verified=${purchase != null} media=${mediaIds.size}")
        require(mediaIds.size <= Review.MAX_MEDIA) { "A review with ${mediaIds.size} media" }
        return Review.submit(reviewIdGenerator.nextReviewId(), productId, userId, rating, authorName, contact, text, purchase, mediaIds, now)
    }

    /**
     * A line of the author's orders, as the order's read model has it: whose product, which color, and whether it was
     * delivered (the order has its delivery and wasn't cancelled since; [deliveredOn] then).
     */
    data class PurchaseLine(
        val orderId: OrderId,
        val orderCode: OrderCode,
        val lineId: Int,
        val productId: ProductId,
        val colorKey: ColorKey,
        val colorName: String,
        val colorHex: String,
        val deliveredOn: Instant?,
        val cancelled: Boolean,
    ) {
        val delivered: Boolean get() = deliveredOn != null && !cancelled

        val purchase: ReviewPurchase get() = ReviewPurchase(orderId, orderCode, lineId, colorKey, colorName, colorHex)
    }

    companion object {
        /** At most this many reviews of one user in [WINDOW] (decision 9). */
        const val MAX_PER_DAY = 5
        val WINDOW: Duration = Duration.ofHours(24)
    }

    // ============ failures ============

    sealed class GateFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object ProductNotFound : GateFailure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        /** A PENDING or APPROVED review of the same product by the same user (409). */
        data object AlreadyExists : GateFailure("REVIEW_ALREADY_EXISTS", FailureKind.CONFLICT)

        /** Five in a day already: wait [retryAfter] (429), at least a second. */
        data class RateLimited(val retryAfter: Duration) :
            GateFailure("RATE_LIMITED", FailureKind.RATE_LIMITED, mapOf("retryAfterSeconds" to ((retryAfter.toMillis() + 999) / 1000).coerceAtLeast(1)))
    }

    sealed class PurchaseFailure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** No order of the author's by that code (404). */
        data object OrderNotFound : PurchaseFailure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The line isn't one of the order's, or is of another product (400, on `orderItemId`). */
        data class InvalidInput(val problem: ResultFailure) : PurchaseFailure("VALIDATION_ERROR", FailureKind.INVALID, listOf(problem))

        /** Not delivered yet, or cancelled (422). */
        data object NotDelivered : PurchaseFailure("REVIEW_PURCHASE_NOT_DELIVERED", FailureKind.BUSINESS_RULE)
    }

    /** What can be wrong with a review's photos or purchase; the field is added where it is found. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object MediaDisabled : Problem("REVIEW_MEDIA_DISABLED")
        data object MediaNotFound : Problem("MEDIA_NOT_FOUND")
        data object LineNotFound : Problem("ORDER_ITEM_NOT_FOUND")
        data object ProductMismatch : Problem("ORDER_ITEM_PRODUCT_MISMATCH")
    }
}
