package com.kiva.domain.aggregate.review

import com.kiva.common.railway.FieldRequired
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.MediaFixtures
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewContact
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.TextTooShort
import com.kiva.domain.aggregate.review.repository.ReviewIdGenerator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What a review can't know alone when it is written (roadmap section 13.2, decisions 1 to 9), and its values. */
class ReviewDomainServiceTest {

    private val now = Instant.parse("2026-10-22T08:00:00Z")
    private val sara = UserId.constructFromPersisted(7)
    private val nila = ProductId.constructFromPersisted(1)
    private val mahak = ProductId.constructFromPersisted(2)
    private val service = ReviewDomainService(object : ReviewIdGenerator {
        override fun nextReviewId() = ReviewId.constructFromPersisted(5531)
    })

    private fun line(order: Long, lineId: Int, product: ProductId, deliveredDaysAgo: Long?, cancelled: Boolean = false) = ReviewDomainService.PurchaseLine(
        OrderId.constructFromPersisted(order), OrderCode.constructFromPersisted("KV-21834$order"), lineId, product, ColorKey.constructFromPersisted("black"),
        "مشکی", "#1F1F1F", deliveredDaysAgo?.let { now.minus(Duration.ofDays(it)) }, cancelled,
    )

    @Nested
    inner class Gate {
        @Test
        fun `a published product, no live review, four today - may write`() {
            assertNull(service.gate(productActive = true, liveExists = false, recent = List(4) { now.minusSeconds(60L * it) }, now = now))
        }

        @Test
        fun `in the contract's order - not published 404, a live review 409, a fifth today 429 with how long`() {
            val fiveToday = listOf(now.minus(Duration.ofHours(23)), now.minus(Duration.ofHours(2)), now.minusSeconds(300), now.minusSeconds(200), now.minusSeconds(100))

            assertEquals(ReviewDomainService.GateFailure.ProductNotFound, service.gate(false, true, fiveToday, now))
            assertEquals(ReviewDomainService.GateFailure.AlreadyExists, service.gate(true, true, fiveToday, now))
            val limited = service.gate(true, false, fiveToday, now)
            assertIs<ReviewDomainService.GateFailure.RateLimited>(limited)
            assertEquals(Duration.ofHours(1), limited.retryAfter, "the oldest of the day leaves in an hour")
        }

        @Test
        fun `one written exactly 24 hours ago no longer counts`() {
            val fiveButOneOld = listOf(now.minus(Duration.ofHours(24))) + List(4) { now.minusSeconds(60L * (it + 1)) }

            assertNull(service.gate(true, false, fiveButOneOld, now))
        }
    }

    @Nested
    inner class Media {
        private val own = MediaFixtures.photo(MediaFixtures.id(1), MediaPurpose.REVIEW_PHOTO, uploadedBy = sara)
        private val others = MediaFixtures.photo(MediaFixtures.id(2), MediaPurpose.REVIEW_PHOTO, uploadedBy = UserId.constructFromPersisted(8))
        private val evidence = MediaFixtures.photo(MediaFixtures.id(3), MediaPurpose.RETURN_EVIDENCE, uploadedBy = sara)

        @Test
        fun `the reviewer's own review photos are fine - none at all is fine with the flag off too`() {
            assertEquals(emptyList(), service.mediaProblems(sara, listOf(own.id), listOf(own), enabled = true))
            assertEquals(emptyList(), service.mediaProblems(sara, emptyList(), emptyList(), enabled = false))
        }

        @Test
        fun `someone else's, another purpose's or one not there are all MEDIA_NOT_FOUND on their field - with the flag off REVIEW_MEDIA_DISABLED`() {
            val ids = listOf(own.id, others.id, evidence.id, MediaFixtures.id(4))

            val problems = service.mediaProblems(sara, ids, listOf(own, others, evidence), enabled = true)

            assertEquals(listOf("mediaIds.1", "mediaIds.2", "mediaIds.3"), problems.map { it.field })
            assertEquals(setOf("MEDIA_NOT_FOUND"), problems.map { it.code }.toSet())
            assertEquals(listOf("mediaIds REVIEW_MEDIA_DISABLED"), service.mediaProblems(sara, listOf(own.id), listOf(own), enabled = false).map { "${it.field} ${it.code}" })
        }
    }

    @Nested
    inner class PurchaseLink {
        private val ordered = listOf(line(1, 1, nila, deliveredDaysAgo = 3), line(1, 2, mahak, deliveredDaysAgo = 3))

        @Test
        fun `named - the line of this product of a delivered order is linked, with its color`() {
            val purchase = service.purchaseLink(nila, OrderCode.constructFromPersisted("KV-218341"), 1, ordered, emptyList()).getOrNull()

            assertEquals(1, purchase?.lineId)
            assertEquals("black" to "مشکی", purchase?.colorKey?.value to purchase?.colorName)
        }

        @Test
        fun `named - none of the author's 404, a line it hasn't 400, another product's 400, not delivered or cancelled 422`() {
            val code = OrderCode.constructFromPersisted("KV-218341")
            val shipped = listOf(line(1, 1, nila, deliveredDaysAgo = null))
            val cancelled = listOf(line(1, 1, nila, deliveredDaysAgo = 3, cancelled = true))

            assertEquals(ReviewDomainService.PurchaseFailure.OrderNotFound, service.purchaseLink(nila, code, 1, null, emptyList()).failureOrNull())
            val missing = service.purchaseLink(nila, code, 9, ordered, emptyList()).failureOrNull()
            assertEquals(listOf("orderItemId ORDER_ITEM_NOT_FOUND"), missing?.causes?.map { "${it.field} ${it.code}" })
            val mismatch = service.purchaseLink(nila, code, 2, ordered, emptyList()).failureOrNull()
            assertEquals(listOf("orderItemId ORDER_ITEM_PRODUCT_MISMATCH"), mismatch?.causes?.map { "${it.field} ${it.code}" })
            assertEquals(ReviewDomainService.PurchaseFailure.NotDelivered, service.purchaseLink(nila, code, 1, shipped, emptyList()).failureOrNull())
            assertEquals(ReviewDomainService.PurchaseFailure.NotDelivered, service.purchaseLink(nila, code, 1, cancelled, emptyList()).failureOrNull())
        }

        @Test
        fun `not named - the latest delivered of two orders is found by itself, a cancelled one never, none is no purchase`() {
            val candidates = listOf(line(1, 1, nila, deliveredDaysAgo = 30), line(2, 3, nila, deliveredDaysAgo = 2), line(3, 1, nila, deliveredDaysAgo = 1, cancelled = true))

            assertEquals(OrderId.constructFromPersisted(2) to 3, service.purchaseLink(nila, null, null, null, candidates).getOrNull()?.let { it.orderId to it.lineId })
            assertNull(service.purchaseLink(nila, null, null, null, listOf(line(3, 1, nila, 1, cancelled = true))).getOrNull())
            assertNull(service.purchaseLink(mahak, null, null, null, candidates).getOrNull())
        }
    }

    @Nested
    inner class Values {
        @Test
        fun `a review's text - trimmed, its lines kept, more than one empty line in a row one, 10 to 1000`() {
            val made = ReviewText.makeNew("  خیلی خوبه\r\n\r\n\r\n\r\nبندش هم بلنده <3  ", ReviewText.MIN_REVIEW, ReviewText.MAX_LENGTH, FieldRequired).getOrNull()

            assertEquals("خیلی خوبه\n\nبندش هم بلنده <3", made?.value)
            assertEquals(FieldRequired, ReviewText.makeNew("   ", 10, 1000, FieldRequired).failureOrNull())
            assertEquals(TextTooShort(10), ReviewText.makeNew("عالیه", 10, 1000, FieldRequired).failureOrNull())
            assertEquals("TEXT_TOO_LONG", ReviewText.makeNew("ا".repeat(1001), 10, 1000, FieldRequired).failureOrNull()?.code)
            assertEquals("TEXT_INVALID", ReviewText.makeNew("خیلی خوبه\u0007 واقعاً", 10, 1000, FieldRequired).failureOrNull()?.code)
        }

        @Test
        fun `a reason too short is the use's own failure, not TEXT_TOO_SHORT`() {
            val required = object : com.kiva.common.railway.ResultFailure("REJECTION_REASON_REQUIRED", com.kiva.common.railway.FailureKind.INVALID) {}

            assertEquals(required, ReviewText.makeNew("نه", 3, 300, required, required).failureOrNull())
        }

        @Test
        fun `a contact is a mobile, digits normalized, or an email in lower case - anything else CONTACT_INVALID - blank none`() {
            assertEquals("09123456789", ReviewContact.makeNew("۰۹۱۲ ۳۴۵ ۶۷۸۹").getOrNull()?.value)
            assertEquals("sara@kiva.ir", ReviewContact.makeNew(" Sara@Kiva.ir ").getOrNull()?.value)
            assertNull(ReviewContact.makeNew("  ").getOrNull())
            assertEquals("CONTACT_INVALID", ReviewContact.makeNew("سارا").failureOrNull()?.code)
            assertEquals("ReviewContact(***)", ReviewContact.makeNew("09123456789").getOrNull().toString())
        }

        @Test
        fun `an author name - blank is none, at most 40`() {
            assertNull(ReviewAuthorName.makeNew(" ").getOrNull())
            assertEquals("سارا", ReviewAuthorName.makeNew(" سارا ").getOrNull()?.value)
            assertEquals("TEXT_TOO_LONG", ReviewAuthorName.makeNew("ا".repeat(41)).failureOrNull()?.code)
        }

        @Test
        fun `a review id that can't be one is REVIEW_NOT_FOUND`() {
            listOf("0", "-3", "abc", null).forEach { assertEquals("REVIEW_NOT_FOUND", ReviewId.makeNew(it).failureOrNull()?.code) }
            assertEquals(12L, ReviewId.makeNew("12").getOrNull()?.value)
        }
    }

    @Test
    fun `the new review gets the next id and the purchase - PENDING`() {
        val review = service.submit(
            nila, sara, com.kiva.domain.aggregate.review.model.valueobject.StarRating.constructFromPersisted(5), null, null,
            ReviewText.constructFromPersisted("کیفیت دوخت عالیه"), line(2, 3, nila, 2).purchase, emptyList(), now,
        )

        assertEquals(5531L, review.id.value)
        assertEquals(3, review.purchase?.lineId)
        assertEquals("PENDING", review.status.name)
    }
}
