package com.kiva.domain.aggregate.review

import com.kiva.common.patch.Patch
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.MediaFixtures
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.entity.Review
import com.kiva.domain.aggregate.review.model.valueobject.ReviewAuthorName
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewPurchase
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus.APPROVED
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus.PENDING
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus.REJECTED
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A review's steps (roadmap section 13.2, decisions 10 to 13): every move support may make, and what its author may change. */
class ReviewTest {

    private val now = Instant.parse("2026-10-22T08:00:00Z")
    private val later = now.plusSeconds(3600)
    private val sara = UserId.constructFromPersisted(7)
    private val support = UserId.constructFromPersisted(900)

    private fun text(value: String) = ReviewText.constructFromPersisted(value)

    private fun pending(purchase: ReviewPurchase? = null) = Review.submit(
        ReviewId.constructFromPersisted(5531), ProductId.constructFromPersisted(1), sara, StarRating.constructFromPersisted(5),
        ReviewAuthorName.constructFromPersisted("سمیرا"), null, text("کیفیت دوخت و چرمش عالیه"), purchase, listOf(MediaFixtures.id(1)), now,
    )

    private fun inStatus(status: ReviewStatus): Review = pending().also {
        when (status) {
            PENDING -> {}
            APPROVED -> it.approve(null, support, now).getOrNull()!!
            REJECTED -> it.reject(text("متن تبلیغاتیه"), support, now).getOrNull()!!
        }
    }

    @Test
    fun `a new review is PENDING, unmoderated, helpful to nobody, out of the rating`() {
        val review = pending()

        assertEquals(PENDING, review.status)
        assertNull(review.moderatedOn)
        assertEquals(0, review.helpfulCount)
        assertFalse(review.countsInRating)
        assertFalse(review.isVerifiedBuyer)
        assertTrue(pending(ReviewPurchase(OrderId.constructFromPersisted(42), OrderCode.constructFromPersisted("KV-218340"), 1, ColorKey.constructFromPersisted("black"), "مشکی", "#1F1F1F")).isVerifiedBuyer)
    }

    @Nested
    inner class Moderation {
        @Test
        fun `PENDING to APPROVED with Kiva's reply at once - in the rating, moderated by support`() {
            val review = pending()

            review.approve(text("ممنون از نظرت"), support, later).getOrNull()!!

            assertEquals(APPROVED, review.status)
            assertTrue(review.countsInRating)
            assertEquals(later to support, review.moderatedOn to review.moderatedBy)
            assertEquals("ممنون از نظرت", review.reply?.text?.value)
            assertEquals(later, review.reply?.repliedOn)
        }

        @Test
        fun `PENDING or APPROVED to REJECTED with the reason - out of the rating, a reply kept`() {
            val approved = inStatus(APPROVED).also { it.reply(text("ممنون"), support, now).getOrNull()!! }

            approved.reject(text("شامل اطلاعات تماس"), support, later).getOrNull()!!
            val pending = pending().also { it.reject(text("تبلیغ"), support, later).getOrNull()!! }

            listOf(approved, pending).forEach { assertEquals(REJECTED, it.status) }
            assertEquals("شامل اطلاعات تماس", approved.rejectionReason?.value)
            assertFalse(approved.countsInRating)
            assertEquals("ممنون", approved.reply?.text?.value, "kept, unseen")
        }

        @Test
        fun `REJECTED to APPROVED again - its reason gone`() {
            val review = inStatus(REJECTED)

            review.approve(null, support, later).getOrNull()!!

            assertEquals(APPROVED, review.status)
            assertNull(review.rejectionReason)
        }

        @Test
        fun `the same status again is INVALID_STATUS_TRANSITION, with where it may go`() {
            val approved = inStatus(APPROVED).approve(null, support, later).failureOrNull()
            val rejected = inStatus(REJECTED).reject(text("دوباره"), support, later).failureOrNull()

            assertIs<Review.InvalidTransition>(approved)
            assertEquals(listOf("REJECTED"), approved.meta["allowed"])
            assertIs<Review.InvalidTransition>(rejected)
            assertEquals(listOf("APPROVED"), rejected.meta["allowed"])
        }

        @Test
        fun `Kiva replies only under an approved review - the reply taken away once, then nothing to take`() {
            val review = inStatus(APPROVED)

            assertIs<Review.InvalidTransition>(pending().reply(text("سلام"), support, now).failureOrNull())
            assertIs<Review.InvalidTransition>(inStatus(REJECTED).reply(text("سلام"), support, now).failureOrNull())
            review.reply(text("ممنون"), support, now).getOrNull()!!
            review.reply(text("ممنون از انرژیت"), support, later).getOrNull()!!

            assertEquals("ممنون از انرژیت" to later, review.reply?.text?.value to review.reply?.repliedOn)
            assertTrue(review.removeReply(later))
            assertFalse(review.removeReply(later))
        }
    }

    @Nested
    inner class Editing {
        @Test
        fun `a PENDING review takes new stars, name and text - an unchanged field stays`() {
            val review = pending()

            val changed = review.edit(Patch.Set(StarRating.constructFromPersisted(4)), Patch.Unchanged, Patch.Set(text("دوختش خوبه ولی بند کوتاهه")), later).getOrNull()!!

            assertTrue(changed)
            assertEquals(4, review.rating.value)
            assertEquals("سمیرا", review.authorName?.value)
            assertEquals("دوختش خوبه ولی بند کوتاهه", review.text.value)
            assertEquals(PENDING, review.status)
            assertEquals(later, review.updatedOn)
            assertEquals(now, review.createdOn)
        }

        @Test
        fun `a REJECTED review edited goes back to the queue, its reason gone - a name set to none is none`() {
            val review = inStatus(REJECTED)

            review.edit(Patch.Unchanged, Patch.Set(null), Patch.Unchanged, later).getOrNull()!!

            assertEquals(PENDING, review.status)
            assertNull(review.rejectionReason)
            assertNull(review.authorName)
        }

        @Test
        fun `nothing new changes nothing, not even a REJECTED one's status`() {
            val review = inStatus(REJECTED)

            val changed = review.edit(Patch.Set(StarRating.constructFromPersisted(5)), Patch.Unchanged, Patch.Unchanged, later).getOrNull()!!

            assertFalse(changed)
            assertEquals(REJECTED, review.status)
            assertEquals(now, review.updatedOn)
        }

        @Test
        fun `an APPROVED review is REVIEW_NOT_EDITABLE`() {
            val failure = inStatus(APPROVED).edit(Patch.Set(StarRating.constructFromPersisted(1)), Patch.Unchanged, Patch.Unchanged, later).failureOrNull()

            assertEquals(Review.NotEditable, failure)
            assertEquals("REVIEW_NOT_EDITABLE", failure?.code)
        }
    }

    @Test
    fun `votes - helpful up and back, never below nothing`() {
        val review = inStatus(APPROVED)

        review.votedHelpful()
        review.votedHelpful()
        review.voteTakenBack()

        assertEquals(1, review.helpfulCount)
        review.voteTakenBack()
        kotlin.test.assertFailsWith<IllegalStateException> { review.voteTakenBack() }
    }

    @Test
    fun `stored data that breaks a rule stops loudly - REJECTED without a reason, APPROVED never moderated`() {
        fun stored(status: ReviewStatus, reason: ReviewText?, moderatedOn: Instant?) = Review.constructFromPersisted(
            ReviewId.constructFromPersisted(1), ProductId.constructFromPersisted(1), sara, StarRating.constructFromPersisted(5), null, null,
            text("کیفیت دوخت عالیه"), status, reason, null, emptyList(), 0, null, now, now, moderatedOn, null, 0,
        )

        kotlin.test.assertFailsWith<IllegalArgumentException> { stored(REJECTED, null, now) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { stored(APPROVED, null, null) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { stored(PENDING, text("دلیل"), null) }
        assertEquals(APPROVED, stored(APPROVED, null, now).status)
    }
}
