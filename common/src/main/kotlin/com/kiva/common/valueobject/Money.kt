package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * An amount of money in **toman**, as a whole number. Never negative.
 *
 * Toman because the API contract states every amount in toman. The only place rials are needed is the
 * payment gateway, which gets [toRials].
 *
 * Why `Long` and not `Double`: `Double` can't hold most decimal values exactly (0.1 + 0.2 = 0.30000000000000004),
 * and rounding errors in money are real lost money. Prices are whole toman, so a whole number is exact.
 * Formatting with separators is the client's job.
 *
 * Overflow throws instead of returning a Result: no real shop can reach it,
 * so if it happens it is a bug (e.g. a wrong quantity), and it must stop loudly, not wrap around to a negative price.
 */
@JvmInline
value class Money private constructor(val toman: Long) : Comparable<Money> {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(toman >= 0) { "Invalid Money $toman" }
    }

    operator fun plus(other: Money): Money = Money(Math.addExact(toman, other.toman))

    /**
     * What is left after taking [other] away (a discount from a price). Money is never negative, so taking more than
     * there is is a bug (e.g. a discount above the price): it stops loudly.
     */
    operator fun minus(other: Money): Money {
        require(other <= this) { "Can't take $other from $this" }
        return Money(toman - other.toman)
    }

    /** Price of [quantity] items. [quantity] is checked earlier (e.g. by a Quantity value object), so negative is a bug. */
    operator fun times(quantity: Int): Money {
        require(quantity >= 0) { "Quantity can't be negative: $quantity" }
        return Money(Math.multiplyExact(toman, quantity.toLong()))
    }

    /** For the payment gateway only: Iranian gateways take rials (1 toman = 10 rials). */
    fun toRials(): Long = Math.multiplyExact(toman, 10L)

    override fun compareTo(other: Money): Int = toman.compareTo(other.toman)

    override fun toString(): String = "$toman toman"

    companion object {
        val ZERO = Money(0)

        fun makeNew(toman: Long): Result<Money, Failure> =
            if (toman >= 0) Result.success(Money(toman)) else Result.failure(Failure.Negative)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(toman: Long): Money = Money(toman)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Negative : Failure("MONEY_NEGATIVE")
    }
}

/** Total of a list of amounts, e.g. all order lines. */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)
