package com.kiva.domain.aggregate.review.model.valueobject

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A product's rating (roadmap section 13.2, decisions 15 and 16): its approved reviews counted, their stars added up,
 * how many gave each star ([stars], index 0 for one star), and how many were by verified buyers. Only an approved
 * review is in it. Kept apart from the product's read model (`productrating_tbl`) and changed only through RatingBook,
 * one review at a time, under the row's lock.
 *
 * Its numbers always agree ([count] = all the stars, [sum] = the stars weighed, [verifiedCount] ≤ [count]); the table's
 * CHECKs are the same rule, the second wall.
 */
class RatingTally private constructor(val count: Int, val sum: Int, val stars: List<Int>, val verifiedCount: Int) {

    init {
        require(stars.size == StarRating.MAX && stars.all { it >= 0 }) { "A rating of stars $stars" }
        require(count == stars.sum()) { "A rating of $count reviews whose stars are $stars" }
        require(sum == stars.withIndex().sumOf { (i, n) -> (i + 1) * n }) { "A rating summing $sum whose stars are $stars" }
        require(verifiedCount in 0..count) { "A rating of $count reviews, $verifiedCount by buyers" }
    }

    /** With one more approved review of [rating] (by a verified buyer when [verified]). */
    fun plus(rating: StarRating, verified: Boolean): RatingTally =
        RatingTally(count + 1, sum + rating.value, stars.mapIndexed { i, n -> if (i == rating.value - 1) n + 1 else n }, verifiedCount + if (verified) 1 else 0)

    /** Without an approved review of [rating] that was in it; one that wasn't is a bug and stops loudly (init). */
    fun minus(rating: StarRating, verified: Boolean): RatingTally =
        RatingTally(count - 1, sum - rating.value, stars.mapIndexed { i, n -> if (i == rating.value - 1) n - 1 else n }, verifiedCount - if (verified) 1 else 0)

    /** The stars on average, one decimal, half up; 0 without reviews. */
    fun average(): Double = if (count == 0) 0.0 else BigDecimal(sum).divide(BigDecimal(count), 1, RoundingMode.HALF_UP).toDouble()

    /** How many gave [starsOf] stars. */
    fun countOf(starsOf: Int): Int = stars[starsOf - 1]

    /** The share of the reviews that gave [starsOf] stars, in percent, one decimal, half up; 0 without reviews. */
    fun percentOf(starsOf: Int): Double =
        if (count == 0) 0.0 else BigDecimal(countOf(starsOf) * 100).divide(BigDecimal(count), 1, RoundingMode.HALF_UP).toDouble()

    /** The share by verified buyers, in whole percent, half up; null without reviews. */
    fun verifiedPercent(): Int? = if (count == 0) null else BigDecimal(verifiedCount * 100).divide(BigDecimal(count), 0, RoundingMode.HALF_UP).toInt()

    override fun equals(other: Any?): Boolean =
        other is RatingTally && other.count == count && other.sum == sum && other.stars == stars && other.verifiedCount == verifiedCount

    override fun hashCode(): Int = listOf(count, sum, stars, verifiedCount).hashCode()

    override fun toString(): String = "RatingTally(count=$count, sum=$sum, stars=$stars, verified=$verifiedCount)"

    companion object {
        /** A product without approved reviews. */
        val NONE = RatingTally(0, 0, List(StarRating.MAX) { 0 }, 0)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(count: Int, sum: Int, stars: List<Int>, verifiedCount: Int): RatingTally = RatingTally(count, sum, stars, verifiedCount)
    }
}
