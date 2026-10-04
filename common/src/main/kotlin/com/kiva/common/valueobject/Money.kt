package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * An amount of money in **rials**, as a whole number. Never negative.
 *
 * Why `Long` and not `Double`: `Double` can't hold most decimal values exactly (0.1 + 0.2 = 0.30000000000000004),
 * and rounding errors in money are real lost money. Rials have no fractions, so a whole number is exact.
 * Converting to toman or formatting with separators is the client's job.
 *
 * Overflow (past ~9.2 quintillion rials) throws instead of returning a Result: no real shop can reach it,
 * so if it happens it is a bug (e.g. a wrong quantity), and it must stop loudly, not wrap around to a negative price.
 */
@JvmInline
value class Money private constructor(val rials: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(rials, other.rials))

    /** Price of [quantity] items. [quantity] is checked earlier (e.g. by a Quantity value object), so negative is a bug. */
    operator fun times(quantity: Int): Money {
        require(quantity >= 0) { "Quantity can't be negative: $quantity" }
        return Money(Math.multiplyExact(rials, quantity.toLong()))
    }

    override fun compareTo(other: Money): Int = rials.compareTo(other.rials)

    override fun toString(): String = "$rials IRR"

    companion object {
        val ZERO = Money(0)

        fun ofRials(rials: Long): Result<Money, Failure> =
            if (rials >= 0) Result.success(Money(rials)) else Result.failure(Failure.Negative)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Negative : Failure("MONEY_NEGATIVE")
    }
}

/** Total of a list of amounts, e.g. all order lines. */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO, Money::plus)
