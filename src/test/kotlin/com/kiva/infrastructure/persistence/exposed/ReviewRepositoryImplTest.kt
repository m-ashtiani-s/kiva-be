package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.ProductFilter
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Pageable
import com.kiva.common.patch.Patch
import com.kiva.common.repository.UniqueTakenException
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
import com.kiva.domain.aggregate.review.model.valueobject.ReviewPurchase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review's rows over kiva_test (roadmap step 8.1): a review stored and read back whole, one live review per user and
 * product (the domain checks first, review_one_live_idx is the wall), votes going with their review, the products'
 * ratings, V33's CHECKs, and the shop's «بیشترین امتیاز» after the read model's unused column went.
 */
class ReviewRepositoryImplTest {

    private val db = TestDatabase.database
    private val reviews = ReviewRepositoryImpl()
    private val votes = ReviewVoteRepositoryImpl()
    private val ratings = ProductRatingRepositoryImpl()
    private val ids = ReviewIdGeneratorImpl()
    private val now = Instant.parse("2026-10-22T08:00:00Z")
    private val product = ProductId.constructFromPersisted(987_654_801)
    private val other = ProductId.constructFromPersisted(987_654_802)
    private val sara = UserId.constructFromPersisted(987_654_801)
    private val support = UserId.constructFromPersisted(900)

    @AfterEach
    fun removeRows() {
        transaction(db) {
            exec("""DELETE FROM review_tbl WHERE "productId" IN (${product.value}, ${other.value}) OR "userId" = ${sara.value}""")
            exec("""DELETE FROM productrating_tbl WHERE "productId" IN (${product.value}, ${other.value})""")
        }
    }

    private fun text(value: String) = ReviewText.constructFromPersisted(value)

    private fun review(of: ProductId = product, by: UserId = sara, purchase: ReviewPurchase? = null) = transaction(db) {
        Review.submit(
            ids.nextReviewId(), of, by, StarRating.constructFromPersisted(4), ReviewAuthorName.constructFromPersisted("سارا"),
            ReviewContact.constructFromPersisted("sara@kiva.ir"), text("خیلی خوبه\n\nبندش هم بلنده"), purchase,
            listOf(MediaId.constructFromPersisted("med_" + "R".repeat(22))), now,
        )
    }

    private val purchase = ReviewPurchase(
        OrderId.constructFromPersisted(42), OrderCode.constructFromPersisted("KV-218340"), 1, ColorKey.constructFromPersisted("black"), "مشکی", "#1F1F1F",
    )

    @Test
    fun `a review stored, approved with a reply, voted for and read back - every field as it was`() {
        val written = review(purchase = purchase)
        transaction(db) { reviews.add(written) }

        transaction(db) {
            val locked = reviews.lock(written.id)!!
            locked.approve(text("ممنون از نظرت"), support, now.plusSeconds(60)).getOrNull()!!
            locked.votedHelpful()
            reviews.save(locked)
            votes.add(written.id, UserId.constructFromPersisted(8), now)
        }
        val read = transaction(db) { reviews.find(written.id)!! }

        assertEquals(ReviewStatus.APPROVED, read.status)
        assertEquals(listOf(4L, 1L, 1L), listOf(read.rating.value.toLong(), read.helpfulCount.toLong(), read.version))
        assertEquals("خیلی خوبه\n\nبندش هم بلنده", read.text.value)
        assertEquals("sara@kiva.ir" to "سارا", read.contact?.value to read.authorName?.value)
        assertEquals(purchase, read.purchase)
        assertEquals("ممنون از نظرت" to support, read.reply?.text?.value to read.reply?.repliedBy)
        assertEquals(written.mediaIds, read.mediaIds)
        assertEquals(now.plusSeconds(60), read.moderatedOn)
        assertEquals(setOf(written.id), transaction(db) { votes.votedBy(UserId.constructFromPersisted(8), listOf(written.id, ids.nextReviewId())) })
    }

    @Test
    fun `a second live review of a product by one user is refused by review_one_live_idx - after the first is REJECTED it goes in`() {
        val first = review()
        transaction(db) { reviews.add(first) }

        assertFailsWith<UniqueTakenException> { transaction(db) { reviews.add(review()) } }
        transaction(db) {
            reviews.lock(first.id)!!.also { it.reject(text("تبلیغ"), support, now).getOrNull()!!; reviews.save(it) }
            reviews.add(review())
        }
        assertEquals(1, transaction(db) { listOfNotNull(reviews.liveOf(sara, product)) }.size)
        assertEquals(setOf(product), transaction(db) { reviews.liveProductIdsOf(sara) })
    }

    @Test
    fun `a rejected review edited back to PENDING while another is live is refused by the index too`() {
        val first = review()
        transaction(db) {
            reviews.add(first)
            reviews.lock(first.id)!!.also { it.reject(text("تبلیغ"), support, now).getOrNull()!!; reviews.save(it) }
            reviews.add(review())
        }

        assertFailsWith<UniqueTakenException> {
            transaction(db) {
                reviews.lock(first.id)!!.also { it.edit(Patch.Set(StarRating.constructFromPersisted(2)), Patch.Unchanged, Patch.Unchanged, now).getOrNull()!!; reviews.save(it) }
            }
        }
    }

    @Test
    fun `a review deleted takes its votes with it - when each was written counts the last day only`() {
        val written = review()
        transaction(db) {
            reviews.add(written)
            votes.add(written.id, UserId.constructFromPersisted(8), now)
        }

        transaction(db) { reviews.delete(written.id) }

        assertNull(transaction(db) { reviews.find(written.id) })
        assertTrue(transaction(db) { !votes.exists(written.id, UserId.constructFromPersisted(8)) })
        transaction(db) { reviews.add(review(of = other)) }
        assertEquals(listOf(now), transaction(db) { reviews.createdSince(sara, now.minusSeconds(1)) })
        assertEquals(emptyList(), transaction(db) { reviews.createdSince(sara, now) })
    }

    @Test
    fun `a product's rating - none at first, made empty when locked, saved, read for a page at once`() {
        assertEquals(RatingTally.NONE, transaction(db) { ratings.find(product) })

        transaction(db) {
            val tally = ratings.lock(product).plus(StarRating.constructFromPersisted(5), true).plus(StarRating.constructFromPersisted(4), false)
            ratings.save(product, tally, now)
        }

        val read = transaction(db) { ratings.find(product) }
        assertEquals(listOf(2, 9, 1), listOf(read.count, read.sum, read.verifiedCount))
        assertEquals(mapOf(product to read), transaction(db) { ratings.findAll(listOf(product, other)) })
    }

    @Test
    fun `V33 refuses a rating whose numbers don't add up, and reviews that break its rules`() {
        fun refused(sql: String): String? = (assertFailsWith<ExposedSQLException>(sql) { transaction(db) { exec(sql) } }.cause as? PSQLException)?.serverErrorMessage?.constraint
        val columns = """"reviewId", "productId", "userId", rating, text, status, "createdOn", "updatedOn""""

        assertEquals(
            "productrating_count_check",
            refused("""INSERT INTO productrating_tbl ("productId", count, sum, stars5, "updatedOn") VALUES (${product.value}, 2, 5, 1, now())"""),
        )
        assertEquals(
            "productrating_sum_check",
            refused("""INSERT INTO productrating_tbl ("productId", count, sum, stars5, "updatedOn") VALUES (${product.value}, 1, 4, 1, now())"""),
        )
        assertEquals(
            "review_rejected_reason_check",
            refused("""INSERT INTO review_tbl ($columns, "moderatedOn") VALUES (987654801, ${product.value}, ${sara.value}, 5, 'خیلی خوبه واقعاً', 'REJECTED', now(), now(), now())"""),
        )
        assertEquals(
            "review_moderated_check",
            refused("""INSERT INTO review_tbl ($columns) VALUES (987654802, ${product.value}, ${sara.value}, 5, 'خیلی خوبه واقعاً', 'APPROVED', now(), now())"""),
        )
        assertEquals(
            "review_tbl_rating_check",
            refused("""INSERT INTO review_tbl ($columns) VALUES (987654803, ${product.value}, ${sara.value}, 6, 'خیلی خوبه واقعاً', 'PENDING', now(), now())"""),
        )
        assertEquals(
            "review_tbl_text_check",
            refused("""INSERT INTO review_tbl ($columns) VALUES (987654804, ${product.value}, ${sara.value}, 5, 'خوبه', 'PENDING', now(), now())"""),
        )
        assertEquals(
            "review_purchase_check",
            refused("""INSERT INTO review_tbl ($columns, "orderId") VALUES (987654805, ${product.value}, ${sara.value}, 5, 'خیلی خوبه واقعاً', 'PENDING', now(), now(), 42)"""),
        )
        assertEquals(
            "review_tbl_contact_check",
            refused("""INSERT INTO review_tbl ($columns, contact) VALUES (987654806, ${product.value}, ${sara.value}, 5, 'خیلی خوبه واقعاً', 'PENDING', now(), now(), 'سارا')"""),
        )
    }

    @Test
    fun `«بیشترین امتیاز» reads the ratings after ratingAvg went - higher average first, then more reviews, none last`() {
        val seeded = SeededCatalog(MutableClock(now))
        runBlocking { seeded.seeder.seed() }
        try {
            val productIds = transaction(db) { ProductQmTbl.select(ProductQmTbl.productId).where { ProductQmTbl.inStock eq true }.map { ProductId.constructFromPersisted(it[ProductQmTbl.productId]) } }
            val (twoOf48, oneOf48, oneOf3) = productIds.take(3)
            transaction(db) {
                fun rate(id: ProductId, vararg stars: Int) = ratings.save(id, stars.fold(ratings.lock(id)) { t, s -> t.plus(StarRating.constructFromPersisted(s), false) }, now)
                rate(oneOf48, 5)
                rate(twoOf48, 5, 5)
                rate(oneOf3, 3)
            }
            val sorted = transaction(db) {
                ProductQmRepositoryImpl().find(ProductFilter(), Pageable.makeNew(1, 100, ProductSorts.RATING_DESC, ProductSorts.names).orFail()).items.map { it.productId }
            }

            assertEquals(listOf(twoOf48, oneOf48, oneOf3), sorted.take(3))
            transaction(db) { exec("""DELETE FROM productrating_tbl WHERE "productId" IN (${twoOf48.value}, ${oneOf48.value}, ${oneOf3.value})""") }
        } finally {
            seeded.deleteSeeded()
        }
    }
}
