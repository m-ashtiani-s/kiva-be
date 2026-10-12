package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId

/**
 * The public code of an order, made from its numeric id (roadmap section 9):
 *
 * ```
 * n    = orderId − 1
 * code = "KV-" + (100000 + (n × A + B) mod 900000)
 * ```
 *
 * `n ↦ (n × A + B) mod 900000` is a permutation of the six-digit space because [A] shares no factor with
 * 900000 = 2⁵·3²·5⁵: every id gets its own code with no check and no retry, and two orders in a row get codes far
 * apart, so the codes tell nobody how many orders the shop has. [A] and [B] are part of every code ever handed out:
 * they never change. After 900,000 orders the same formula goes on over seven digits, then eight (the contract allows
 * 4 to 8).
 */
object OrderCodePolicy {

    /** A prime, so not divisible by 2, 3 or 5; about 0.618 of the space, so neighbours land far apart. */
    private const val A = 556_243L

    /** Chosen so that the first order is KV-218340, the contract's example. */
    private const val B = 118_340L

    private const val MAX_DIGITS = 8

    fun codeOf(id: OrderId): OrderCode {
        var n = id.value - 1
        var digits = 6
        var space = 900_000L
        // Each tier of digits takes the ids after the tier before it: 900,000 six-digit codes, then 9,000,000, ...
        while (n >= space) {
            n -= space
            digits++
            space *= 10
            check(digits <= MAX_DIGITS) { "No order code left for order ${id.value}" }
        }
        val lowest = space / 9 // 100000 for six digits
        return OrderCode.constructFromPersisted("KV-${lowest + (n * A + B) % space}")
    }
}
