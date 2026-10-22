package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.usecase.review.usecase.query.ReviewSorts
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewContact
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewPurchase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewReply
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.review.repository.ProductRatingRepository
import com.kiva.domain.aggregate.review.repository.ReviewIdGenerator
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import com.kiva.domain.aggregate.review.repository.ReviewVoteRepository
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.ProductRatingTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewVoteTbl
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * review_tbl. Runs inside the use case's transaction and never opens one itself. A change is written only over the
 * version it was read with ([save]); the lock is a row lock (`FOR UPDATE`, lock 11). The partial unique index on a live
 * review per user and product becomes UniqueTakenException; any other refusal is a bug and goes up as it is.
 */
class ReviewRepositoryImpl : ReviewRepository {

    override fun add(review: Review) = writing(review) {
        ReviewTbl.insert {
            it[reviewId] = review.id.value
            it[productId] = review.productId.value
            it[userId] = review.userId.value
            it[contact] = review.contact?.value
            it[orderId] = review.purchase?.orderId?.value
            it[orderCode] = review.purchase?.orderCode?.value
            it[lineId] = review.purchase?.lineId
            it[colorKey] = review.purchase?.colorKey?.value
            it[colorName] = review.purchase?.colorName
            it[colorHex] = review.purchase?.colorHex
            it[mediaIds] = review.mediaIds.map(MediaId::value)
            it[createdOn] = review.createdOn.utc()
            it[version] = review.version
            changes(review, it)
        }
    }

    override fun find(id: ReviewId): Review? = ReviewTbl.selectAll().where { ReviewTbl.reviewId eq id.value }.singleOrNull()?.toReview()

    override fun lock(id: ReviewId): Review? = ReviewTbl.selectAll().where { ReviewTbl.reviewId eq id.value }.forUpdate().singleOrNull()?.toReview()

    override fun delete(id: ReviewId) {
        ReviewTbl.deleteWhere { reviewId eq id.value }
    }

    override fun liveOf(userId: UserId, productId: ProductId): Review? =
        ReviewTbl.selectAll()
            .where { (ReviewTbl.userId eq userId.value) and (ReviewTbl.productId eq productId.value) and (ReviewTbl.status inList LIVE) }
            .singleOrNull()?.toReview()

    override fun createdSince(userId: UserId, since: Instant): List<Instant> =
        ReviewTbl.select(ReviewTbl.createdOn).where { (ReviewTbl.userId eq userId.value) and (ReviewTbl.createdOn greater since.utc()) }
            .map { it[ReviewTbl.createdOn].toInstant() }

    override fun pageOfUser(userId: UserId, pageable: Pageable): Page<Review> =
        ReviewTbl.selectAll().where { ReviewTbl.userId eq userId.value }
            .paginate(pageable, NEWEST_FIRST, tiebreaker = ReviewTbl.reviewId, tiebreakerOrder = SortOrder.DESC) { it.toReview() }

    override fun pagePublic(productId: ProductId, filter: ReviewRepository.PublicFilter, pageable: Pageable): Page<Review> {
        var condition = (ReviewTbl.productId eq productId.value) and (ReviewTbl.status eq ReviewStatus.APPROVED)
        filter.rating?.let { condition = condition and (ReviewTbl.rating eq it.value.toShort()) }
        if (filter.withMedia) condition = condition and HasMedia
        if (filter.verifiedOnly) condition = condition and ReviewTbl.orderId.isNotNull()
        return ReviewTbl.selectAll().where { condition }
            .paginate(pageable, PUBLIC_ORDERS, tiebreaker = ReviewTbl.reviewId, tiebreakerOrder = SortOrder.DESC) { it.toReview() }
    }

    override fun pendingOf(userId: UserId, productId: ProductId): List<Review> =
        ReviewTbl.selectAll()
            .where { (ReviewTbl.userId eq userId.value) and (ReviewTbl.productId eq productId.value) and (ReviewTbl.status eq ReviewStatus.PENDING) }
            .orderBy(ReviewTbl.createdOn to SortOrder.DESC, ReviewTbl.reviewId to SortOrder.DESC)
            .map { it.toReview() }

    override fun pageAdmin(filter: ReviewRepository.AdminFilter, pageable: Pageable): Page<Review> {
        val conditions = buildList {
            filter.status?.let { add(ReviewTbl.status eq it) }
            filter.productId?.let { add(ReviewTbl.productId eq it.value) }
            filter.hasReply?.let { add(if (it) ReviewTbl.replyText.isNotNull() else ReviewTbl.replyText.isNull()) }
        }
        val query = ReviewTbl.selectAll()
        if (conditions.isNotEmpty()) query.where { conditions.reduce { a, b -> a and b } }
        return query.paginate(pageable, NEWEST_FIRST, tiebreaker = ReviewTbl.reviewId, tiebreakerOrder = SortOrder.DESC) { it.toReview() }
    }

    override fun countPending(): Int =
        ReviewTbl.selectAll().where { ReviewTbl.status eq ReviewStatus.PENDING }.count().toInt()

    override fun countsOfUser(userId: UserId): Map<ReviewStatus, Int> {
        val count = ReviewTbl.reviewId.count()
        return ReviewTbl.select(ReviewTbl.status, count).where { ReviewTbl.userId eq userId.value }.groupBy(ReviewTbl.status)
            .associate { it[ReviewTbl.status] to it[count].toInt() }
    }

    override fun liveProductIdsOf(userId: UserId): Set<ProductId> =
        ReviewTbl.select(ReviewTbl.productId).where { (ReviewTbl.userId eq userId.value) and (ReviewTbl.status inList LIVE) }
            .map { ProductId.constructFromPersisted(it[ReviewTbl.productId]) }.toSet()

    override fun save(review: Review) = writing(review) {
        val updated = ReviewTbl.update({ (ReviewTbl.reviewId eq review.id.value) and (ReviewTbl.version eq review.version) }) {
            changes(review, it)
            it[version] = review.version + 1
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(review.id.value.toString()))
    }

    private fun writing(review: Review, write: () -> Unit) {
        try {
            write()
        } catch (e: ExposedSQLException) {
            if (e.refusedConstraint() == ReviewTbl.ONE_LIVE_KEY) throw UniqueTakenException("a live review of product ${review.productId.value} by user ${review.userId.value}", e)
            throw e
        }
    }

    /** What may change after it was written. */
    private fun changes(review: Review, it: UpdateBuilder<*>) {
        it[ReviewTbl.rating] = review.rating.value.toShort()
        it[ReviewTbl.authorName] = review.authorName?.value
        it[ReviewTbl.text] = review.text.value
        it[ReviewTbl.status] = review.status
        it[ReviewTbl.rejectionReason] = review.rejectionReason?.value
        it[ReviewTbl.helpfulCount] = review.helpfulCount
        it[ReviewTbl.replyText] = review.reply?.text?.value
        it[ReviewTbl.repliedOn] = review.reply?.repliedOn?.utc()
        it[ReviewTbl.repliedBy] = review.reply?.repliedBy?.value
        it[ReviewTbl.updatedOn] = review.updatedOn.utc()
        it[ReviewTbl.moderatedOn] = review.moderatedOn?.utc()
        it[ReviewTbl.moderatedBy] = review.moderatedBy?.value
    }

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("REVIEW")
        val LIVE = listOf(ReviewStatus.PENDING, ReviewStatus.APPROVED)

        /** The one order of a customer's own list and of the panel's queue; the pageable's sort is ignored. */
        val NEWEST_FIRST: SortOrders = mapOf(MINE to listOf(ReviewTbl.createdOn to SortOrder.DESC))

        const val MINE = "newest"

        /** ReviewSorts in the database; paginate adds the review's id, newest first, last. */
        val PUBLIC_ORDERS: SortOrders = mapOf(
            ReviewSorts.NEWEST to listOf(ReviewTbl.createdOn to SortOrder.DESC),
            ReviewSorts.HIGHEST to listOf(ReviewTbl.rating to SortOrder.DESC, ReviewTbl.createdOn to SortOrder.DESC),
            ReviewSorts.LOWEST to listOf(ReviewTbl.rating to SortOrder.ASC, ReviewTbl.createdOn to SortOrder.DESC),
            ReviewSorts.HELPFUL to listOf(ReviewTbl.helpfulCount to SortOrder.DESC, ReviewTbl.createdOn to SortOrder.DESC),
        )
    }
}

/** A review with at least one photo or video. */
private object HasMedia : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("jsonb_array_length(review_tbl.\"mediaIds\") > 0")
    }
}

/** A review as the row has it; for the review repository and the read side alike. */
internal fun ResultRow.toReview(): Review = Review.constructFromPersisted(
    id = ReviewId.constructFromPersisted(this[ReviewTbl.reviewId]),
    productId = ProductId.constructFromPersisted(this[ReviewTbl.productId]),
    userId = UserId.constructFromPersisted(this[ReviewTbl.userId]),
    rating = StarRating.constructFromPersisted(this[ReviewTbl.rating].toInt()),
    authorName = this[ReviewTbl.authorName]?.let { ReviewAuthorName.constructFromPersisted(it) },
    contact = this[ReviewTbl.contact]?.let { ReviewContact.constructFromPersisted(it) },
    text = ReviewText.constructFromPersisted(this[ReviewTbl.text]),
    status = this[ReviewTbl.status],
    rejectionReason = this[ReviewTbl.rejectionReason]?.let { ReviewText.constructFromPersisted(it) },
    purchase = this[ReviewTbl.orderId]?.let { orderId ->
        ReviewPurchase(
            OrderId.constructFromPersisted(orderId), OrderCode.constructFromPersisted(this[ReviewTbl.orderCode]!!), this[ReviewTbl.lineId]!!,
            ColorKey.constructFromPersisted(this[ReviewTbl.colorKey]!!), this[ReviewTbl.colorName]!!, this[ReviewTbl.colorHex]!!,
        )
    },
    mediaIds = this[ReviewTbl.mediaIds].map { MediaId.constructFromPersisted(it) },
    helpfulCount = this[ReviewTbl.helpfulCount],
    reply = this[ReviewTbl.replyText]?.let { text ->
        ReviewReply(ReviewText.constructFromPersisted(text), this[ReviewTbl.repliedOn]!!.toInstant(), UserId.constructFromPersisted(this[ReviewTbl.repliedBy]!!))
    },
    createdOn = this[ReviewTbl.createdOn].toInstant(),
    updatedOn = this[ReviewTbl.updatedOn].toInstant(),
    moderatedOn = this[ReviewTbl.moderatedOn]?.toInstant(),
    moderatedBy = this[ReviewTbl.moderatedBy]?.let { UserId.constructFromPersisted(it) },
    version = this[ReviewTbl.version],
)

private fun Instant.utc() = atOffset(ZoneOffset.UTC)

/** Takes the next number from the sequence of V33__reviews.sql. Runs inside the use case's transaction. */
class ReviewIdGeneratorImpl : ReviewIdGenerator {
    override fun nextReviewId(): ReviewId =
        TransactionManager.current().exec("SELECT nextval('${ReviewTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            ReviewId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${ReviewTbl.ID_SEQUENCE}') returned nothing")
}

/** reviewvote_tbl. Runs inside the use case's transaction. */
class ReviewVoteRepositoryImpl : ReviewVoteRepository {

    override fun exists(reviewId: ReviewId, userId: UserId): Boolean =
        ReviewVoteTbl.selectAll().where { (ReviewVoteTbl.reviewId eq reviewId.value) and (ReviewVoteTbl.userId eq userId.value) }.count() > 0

    override fun add(reviewId: ReviewId, userId: UserId, now: Instant) {
        ReviewVoteTbl.insert {
            it[ReviewVoteTbl.reviewId] = reviewId.value
            it[ReviewVoteTbl.userId] = userId.value
            it[createdOn] = now.utc()
        }
    }

    override fun remove(reviewId: ReviewId, userId: UserId) {
        ReviewVoteTbl.deleteWhere { (ReviewVoteTbl.reviewId eq reviewId.value) and (ReviewVoteTbl.userId eq userId.value) }
    }

    override fun votedBy(userId: UserId, reviewIds: Collection<ReviewId>): Set<ReviewId> {
        if (reviewIds.isEmpty()) return emptySet()
        return ReviewVoteTbl.select(ReviewVoteTbl.reviewId)
            .where { (ReviewVoteTbl.userId eq userId.value) and (ReviewVoteTbl.reviewId inList reviewIds.map { it.value }) }
            .map { ReviewId.constructFromPersisted(it[ReviewVoteTbl.reviewId]) }.toSet()
    }
}

/** productrating_tbl. Runs inside the use case's transaction; the lock is the row's (`FOR UPDATE`), right after the review's (lock 11). */
class ProductRatingRepositoryImpl : ProductRatingRepository {

    override fun lock(productId: ProductId): RatingTally {
        ProductRatingTbl.insertIgnore {
            it[ProductRatingTbl.productId] = productId.value
            it[count] = 0
            it[sum] = 0
            starColumns.forEach { column -> it[column] = 0 }
            it[verifiedCount] = 0
            it[updatedOn] = Instant.EPOCH.utc()
        }
        return ProductRatingTbl.selectAll().where { ProductRatingTbl.productId eq productId.value }.forUpdate().single().toTally()
    }

    override fun find(productId: ProductId): RatingTally =
        ProductRatingTbl.selectAll().where { ProductRatingTbl.productId eq productId.value }.singleOrNull()?.toTally() ?: RatingTally.NONE

    override fun findAll(productIds: Collection<ProductId>): Map<ProductId, RatingTally> {
        if (productIds.isEmpty()) return emptyMap()
        return ProductRatingTbl.selectAll().where { ProductRatingTbl.productId inList productIds.map { it.value } }
            .associate { ProductId.constructFromPersisted(it[ProductRatingTbl.productId]) to it.toTally() }
    }

    override fun save(productId: ProductId, tally: RatingTally, now: Instant) {
        val updated = ProductRatingTbl.update({ ProductRatingTbl.productId eq productId.value }) {
            it[count] = tally.count
            it[sum] = tally.sum
            starColumns.forEachIndexed { i, column -> it[column] = tally.stars[i] }
            it[verifiedCount] = tally.verifiedCount
            it[updatedOn] = now.utc()
        }
        check(updated == 1) { "The rating of product ${productId.value} is saved without being locked first" }
    }

    private fun ResultRow.toTally(): RatingTally = RatingTally.constructFromPersisted(
        this[ProductRatingTbl.count], this[ProductRatingTbl.sum], ProductRatingTbl.starColumns.map { this[it] }, this[ProductRatingTbl.verifiedCount],
    )
}
