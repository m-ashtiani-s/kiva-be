package com.kiva.application.usecase.review.usecase.command

import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.review.ReviewPurchases
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.forField
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.review.ReviewDomainService
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewContact
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * A customer writes a review of a product (`POST /products/{productId}/reviews`; roadmap section 13.6), in the
 * contract's order of rules:
 *
 * 1. the shape, every problem at once (400): 1 to 5 stars, a name of at most 40, a mobile or an email to reach them, a
 *    text of 10 to 1000, an order's code and line together or neither, at most 5 well-formed uploads, each once;
 * 2. in one transaction: the product published (404 PRODUCT_NOT_FOUND; a path that can't be one too); no live review of
 *    it by them (409 REVIEW_ALREADY_EXISTS); fewer than 5 of theirs in the last 24 hours (429 with Retry-After); the
 *    uploads (the flag off: REVIEW_MEDIA_DISABLED; not their own review photos: MEDIA_NOT_FOUND); the purchase it is
 *    linked to (named: 404 ORDER_NOT_FOUND, 400 on `orderItemId`, 422 REVIEW_PURCHASE_NOT_DELIVERED; not named: their
 *    latest delivered line of the product, found by itself); then the PENDING review stored and its uploads marked used.
 *
 * Two at once: review_one_live_idx refuses the second, answered as the same 409. Nothing in the change log (the
 * customer's), no SMS. Returns the new review's id; the route reads it with GetMyReviewUc.
 */
class CreateReviewUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val reviewRepository: ReviewRepository,
    private val reviewDomainService: ReviewDomainService,
    private val reviewPurchases: ReviewPurchases,
    private val orderByCode: OrderByCode,
    private val mediaRepository: MediaRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : CommandUseCase<CreateReviewUc.Cmd, ReviewId, CreateReviewUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; personal fields never go into a log ([toString]). */
    data class Cmd(
        val userId: UserId,
        val productId: String,
        val rating: Int?,
        val authorName: String?,
        val contact: String?,
        val text: String?,
        val orderCode: String?,
        val orderItemId: Int?,
        val mediaIds: List<String>?,
    ) {
        override fun toString(): String =
            "Cmd(userId=${userId.value}, productId=$productId, rating=$rating, linked=${orderCode != null}, media=${mediaIds?.size})"
    }

    private data class Input(
        val rating: StarRating,
        val authorName: ReviewAuthorName?,
        val contact: ReviewContact?,
        val text: ReviewText,
        val orderCode: String?,
        val orderItemId: Int?,
        val mediaIds: List<MediaId>,
    )

    override suspend fun execute(command: Cmd): Result<ReviewId, Failure> {
        val productId = command.productId.trim().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
        logger.info("CreateReviewUc.execute userId=${command.userId.value} productId=${productId?.value ?: "(not an id)"} rating=${command.rating}")
        val input = when (val checked = inputOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (productId == null) return Result.failure(Failure.Refused(ReviewDomainService.GateFailure.ProductNotFound))

        return transactionManager.inTransaction {
            val now = clock.instant()
            val active = productQmRepository.findBriefs(listOf(productId))[productId]?.status == ProductStatus.ACTIVE
            val live = reviewRepository.liveOf(command.userId, productId) != null
            val recent = reviewRepository.createdSince(command.userId, now.minus(ReviewDomainService.WINDOW))
            reviewDomainService.gate(active, live, recent, now)?.let { return@inTransaction Result.failure(Failure.Refused(it)) }

            val found = if (input.mediaIds.isEmpty()) emptyList() else mediaRepository.findAll(input.mediaIds)
            val mediaProblems = reviewDomainService.mediaProblems(command.userId, input.mediaIds, found, shopSettingsRepository.get().features.reviewMedia)
            if (mediaProblems.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(mediaProblems))

            val code: OrderCode? = input.orderCode?.let { orderByCode.codeOf(it) ?: return@inTransaction Result.failure(Failure.OrderNotFound) }
            val ordered = code?.let { reviewPurchases.ofOrder(command.userId, it) }
            val candidates = if (code == null) reviewPurchases.deliveredOf(command.userId) else emptyList()
            val purchase = when (val linked = reviewDomainService.purchaseLink(productId, code, input.orderItemId, ordered, candidates)) {
                is Result.Success -> linked.value
                is Result.Failure -> return@inTransaction Result.failure(failureOf(linked.failure))
            }

            val review: Review = reviewDomainService.submit(
                productId, command.userId, input.rating, input.authorName, input.contact, input.text, purchase, input.mediaIds, now,
            )
            try {
                reviewRepository.add(review)
            } catch (e: UniqueTakenException) {
                logger.info("CreateReviewUc: ${e.message}")
                return@inTransaction Result.failure(Failure.Refused(ReviewDomainService.GateFailure.AlreadyExists))
            }
            if (input.mediaIds.isNotEmpty() && mediaRepository.markAttached(input.mediaIds, now) < input.mediaIds.size) {
                // An upload removed between reading and marking it (the 48-hour clean-up): as if it had never been there.
                return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("mediaIds", ReviewDomainService.Problem.MediaNotFound))))
            }
            logger.info("CreateReviewUc: review ${review.id.value} of product ${productId.value} written, verified=${review.isVerifiedBuyer}")
            Result.success(review.id)
        }
    }

    /** The body's shape, every problem at once, each under its field. */
    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val rating = when (val raw = command.rating) {
            null -> null.also { problems += FieldFailure("rating", FieldRequired) }
            else -> when (val made = StarRating.makeNew(raw)) {
                is Result.Success -> made.value
                is Result.Failure -> null.also { problems += made.failure }
            }
        }
        val authorName = when (val made = ReviewAuthorName.makeNew(command.authorName).forField("authorName")) {
            is Result.Success -> made.value
            is Result.Failure -> null.also { problems += made.failure }
        }
        val contact = when (val made = ReviewContact.makeNew(command.contact)) {
            is Result.Success -> made.value
            is Result.Failure -> null.also { problems += made.failure }
        }
        val text = when (val made = ReviewText.makeNew(command.text, ReviewText.MIN_REVIEW, ReviewText.MAX_LENGTH, FieldRequired).forField("text")) {
            is Result.Success -> made.value
            is Result.Failure -> null.also { problems += made.failure }
        }
        val orderCode = command.orderCode?.trim()?.ifEmpty { null }
        if (orderCode != null && command.orderItemId == null) problems += FieldFailure("orderItemId", FieldRequired)
        if (orderCode == null && command.orderItemId != null) problems += FieldFailure("orderCode", FieldRequired)

        val raw = command.mediaIds.orEmpty()
        val mediaIds = if (raw.size > Review.MAX_MEDIA) {
            problems += FieldFailure("mediaIds", TooManyItems(Review.MAX_MEDIA))
            emptyList()
        } else {
            val seen = mutableSetOf<MediaId>()
            raw.mapIndexedNotNull { i, id ->
                when (val parsed = MediaId.makeNew(id)) {
                    is Result.Failure -> null.also { problems += FieldFailure("mediaIds.$i", parsed.failure) }
                    is Result.Success -> if (seen.add(parsed.value)) parsed.value else null.also { problems += FieldFailure("mediaIds.$i", ListItemDuplicate) }
                }
            }
        }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        return Result.success(Input(checkNotNull(rating), authorName, contact, checkNotNull(text), orderCode, command.orderItemId, mediaIds))
    }

    private fun failureOf(failure: ReviewDomainService.PurchaseFailure): Failure = when (failure) {
        ReviewDomainService.PurchaseFailure.OrderNotFound -> Failure.OrderNotFound
        is ReviewDomainService.PurchaseFailure.InvalidInput -> Failure.InvalidInput(failure.causes)
        ReviewDomainService.PurchaseFailure.NotDelivered -> Failure.NotDelivered
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Every problem of the body, the uploads or the purchase's line, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** PRODUCT_NOT_FOUND (404), REVIEW_ALREADY_EXISTS (409) or RATE_LIMITED (429, with `meta.retryAfterSeconds`). */
        data class Refused(val refusal: ReviewDomainService.GateFailure) : Failure(refusal.code, refusal.kind, meta = refusal.meta)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The order it names isn't delivered, or was cancelled (422). */
        data object NotDelivered : Failure("REVIEW_PURCHASE_NOT_DELIVERED", FailureKind.BUSINESS_RULE)
    }
}
