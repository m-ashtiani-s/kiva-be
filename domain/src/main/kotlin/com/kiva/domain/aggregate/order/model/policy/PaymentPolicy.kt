package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
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

    /**
     * An attempt sent to the gateway at [redirectedOn] whose shopper never came back is abandoned from [GATEWAY_WINDOW]
     * on: nobody is on the bank's page any more, so it no longer holds its order's stock, and once the gateway says it
     * wasn't paid it is closed (EXPIRED).
     */
    fun isAbandoned(redirectedOn: Instant, now: Instant): Boolean = !now.isBefore(redirectedOn.plus(GATEWAY_WINDOW))

    /** How long after a payment its gateway still gives the money back at once (Zarinpal's reverse). */
    val REVERSE_WINDOW: Duration = Duration.ofMinutes(30)

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

    /** Where a paid order pays what a change left owed (roadmap section 8.5): being prepared again, its photos sent, or another change asked. */
    val BALANCE_PAYABLE: Set<OrderStatus> = setOf(OrderStatus.PROCESSING, OrderStatus.PHOTO_SENT, OrderStatus.CHANGE_REQUESTED)

    /**
     * An attempt to pay what a change left owed may start now: something is owed and the order is in [BALANCE_PAYABLE]. It
     * has no time limit: the stock was held when the order was placed, and the order doesn't ship until it is paid.
     */
    fun canPayBalance(status: OrderStatus, balanceDue: Money): Boolean = balanceDue > Money.ZERO && status in BALANCE_PAYABLE

    /** The result page of a balance's attempt offers «تلاش دوباره»: as [canRetry], while the balance may still be paid. */
    fun canRetryBalance(payment: PaymentStatus, order: OrderStatus, balanceDue: Money): Boolean =
        payment.outcome != PaymentOutcome.SUCCEEDED && payment != PaymentStatus.VERIFYING && canPayBalance(order, balanceDue)

    /**
     * Why an order may not have another attempt to pay its balance, or null if it may: it had [MAX_ATTEMPTS_PER_ORDER] of
     * them (counted apart from the attempts to pay the order itself). Time doesn't lift it either; with no time to pay to
     * wait for, the wait given is [GATEWAY_WINDOW], after which an attempt still open can no longer settle.
     */
    fun refusalToPayBalance(balanceAttempts: Int): Refusal.TooManyAttempts? =
        if (balanceAttempts < MAX_ATTEMPTS_PER_ORDER) null else Refusal.TooManyAttempts(GATEWAY_WINDOW)

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
