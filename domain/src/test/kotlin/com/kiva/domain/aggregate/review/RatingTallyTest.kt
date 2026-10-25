package com.kiva.domain.aggregate.review

import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A product's rating, worked out by hand (roadmap section 13.2, decision 16). */
class RatingTallyTest {

    private fun stars(n: Int) = StarRating.constructFromPersisted(n)

    @Test
    fun `three reviews of 5, 4 and 4 - 4,3 on average, 66,7 percent four stars, 67 percent by buyers`() {
        val tally = RatingTally.NONE.plus(stars(5), verified = true).plus(stars(4), verified = true).plus(stars(4), verified = false)

        assertEquals(listOf(3, 13, 2), listOf(tally.count, tally.sum, tally.verifiedCount))
        assertEquals(4.3, tally.average())
        assertEquals(66.7, tally.percentOf(4))
        assertEquals(33.3, tally.percentOf(5))
        assertEquals(0.0, tally.percentOf(1))
        assertEquals(67, tally.verifiedPercent())
    }

    @Test
    fun `half rounds up - 4 and 5 is 4,5, one of eight is 12,5 percent`() {
        assertEquals(4.5, RatingTally.NONE.plus(stars(4), false).plus(stars(5), false).average())
        val eight = (1..7).fold(RatingTally.NONE) { t, _ -> t.plus(stars(5), false) }.plus(stars(1), true)
        assertEquals(12.5, eight.percentOf(1))
        assertEquals(13, eight.verifiedPercent())
    }

    @Test
    fun `minus back to nothing - the same as no reviews, 0 and no buyers' share`() {
        val tally = RatingTally.NONE.plus(stars(3), true).minus(stars(3), true)

        assertEquals(RatingTally.NONE, tally)
        assertEquals(0.0, tally.average())
        assertNull(tally.verifiedPercent())
    }

    @Test
    fun `taking away a review that isn't in it, or numbers that don't agree, stop loudly`() {
        assertFailsWith<IllegalArgumentException> { RatingTally.NONE.minus(stars(5), false) }
        assertFailsWith<IllegalArgumentException> { RatingTally.NONE.plus(stars(5), false).minus(stars(5), true) }
        assertFailsWith<IllegalArgumentException> { RatingTally.constructFromPersisted(2, 9, listOf(0, 0, 0, 1, 0), 0) }
        assertFailsWith<IllegalArgumentException> { RatingTally.constructFromPersisted(1, 3, listOf(0, 0, 1, 0, 0), 2) }
    }
}
