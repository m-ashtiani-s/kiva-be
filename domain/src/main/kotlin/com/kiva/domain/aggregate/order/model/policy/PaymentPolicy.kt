package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentOutcome
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import java.time.Duration
import java.time.Instant

/**
 * When an order may be paid, and the limits that keep a robot from holding the shop's stock with unpaid orders
 * (roadmap sections 3, row 8, and 10.8). Pure: the caller reads the clock and the facts.
 */
object PaymentPolicy {

    /**
     * How long a shopper may still be on the gateway's page after an attempt started. An order with an attempt this
     * young is not expired, even after its `holdUntil`, so a payment made at the last minute still finds its stock.
     * The longest an unpaid order holds stock is so about `holdUntil` + this (an open question of the roadmap).
     */
    val GATEWAY_WINDOW: Duration = Duration.ofMinutes(20)

    /** Unpaid orders a customer may have holding stock at once; one more is 429. */
    const val MAX_UNPAID_ORDERS = 3

    /** Payment attempts an order may have; one more is 429, and only «خرید دوباره» is left. */
    const val MAX_ATTEMPTS_PER_ORDER = 10

    /**
     * A new payment attempt may start: the order is unpaid and its time to pay hasn't ended. A callback of an attempt
     * that started in time is still taken after `holdUntil` (until the order expires); only new attempts stop then.
     */
    fun canStartPayment(status: OrderStatus, holdUntil: Instant, now: Instant): Boolean = status.isAwaitingPayment && now.isBefore(holdUntil)

    /**
     * The result page of an attempt offers «تلاش دوباره» (`PaymentResult.canRetry`): the attempt didn't succeed, it isn't
     * being confirmed right now (the page waits for that instead: a new attempt then could be paid twice over), and a
     * new attempt may start ([canStartPayment]).
     */
    fun canRetry(payment: PaymentStatus, order: OrderStatus, holdUntil: Instant, now: Instant): Boolean =
        payment.outcome != PaymentOutcome.SUCCEEDED && payment != PaymentStatus.VERIFYING && canStartPayment(order, holdUntil, now)

    /**
     * Why the customer may not place another order now, or null if they may: [MAX_UNPAID_ORDERS] of their unpaid
     * orders still hold stock. The wait is until enough of them stop holding it for one more to fit.
     *
     * @param holdingUntil when each of the customer's unpaid orders that still hold stock stops holding it.
     */
    fun refusalToPlace(holdingUntil: List<Instant>, now: Instant): Refusal.TooManyUnpaidOrders? {
        if (holdingUntil.size < MAX_UNPAID_ORDERS) return null
        // When this one stops holding, MAX_UNPAID_ORDERS - 1 are left.
        val mustEnd = holdingUntil.sorted()[holdingUntil.size - MAX_UNPAID_ORDERS]
        return Refusal.TooManyUnpaidOrders(Duration.between(now, mustEnd))
    }

    /**
     * Why an order may not have another payment attempt, or null if it may: it had [MAX_ATTEMPTS_PER_ORDER]. Time
     * doesn't lift this limit, so the wait given is the rest of the order's time to pay; after it only «خرید دوباره»
     * is left.
     */
    fun refusalToAttempt(attempts: Int, holdUntil: Instant, now: Instant): Refusal.TooManyAttempts? =
        if (attempts < MAX_ATTEMPTS_PER_ORDER) null else Refusal.TooManyAttempts(Duration.between(now, holdUntil))

    /**
     * Too many at once. Every refusal is a 429 and tells the client how long to wait, rounded up to a whole second and
     * never less than one (`retryAfterSeconds`, also sent as the Retry-After header).
     */
    sealed class Refusal(code: String, retryAfter: Duration) :
        ResultFailure(code, FailureKind.RATE_LIMITED, meta = mapOf("retryAfterSeconds" to wholeSecondsOf(retryAfter))) {

        abstract val retryAfter: Duration

        data class TooManyUnpaidOrders(override val retryAfter: Duration) : Refusal("RATE_LIMITED", retryAfter)

        data class TooManyAttempts(override val retryAfter: Duration) : Refusal("RATE_LIMITED", retryAfter)
    }

    private fun wholeSecondsOf(duration: Duration): Long = ((duration.toMillis() + 999) / 1000).coerceAtLeast(1)
}
