package com.kiva.domain.aggregate.payment.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPurpose
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.CANCELLED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.EXPIRED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.FAILED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.INITIATED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.REDIRECTED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.REFUNDED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.START_FAILED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.SUCCEEDED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.VERIFYING
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import java.time.Instant

/**
 * One attempt to pay an order through a gateway (roadmap section 10.1). Kept as a row, not as events: it is the record
 * of a transaction outside the shop, with a short life, and its talk with the gateway is kept apart, call by call, in
 * the payment log. An order may have several attempts; the gateway is never called inside a transaction, so this state
 * machine is what makes every half-done step safe to pick up again:
 *
 * ```
 * INITIATED ─┬─ the gateway opened a session ─→ REDIRECTED ─┬─ the shopper cancelled ──────→ CANCELLED
 *            └─ refused, or no answer ────────→ START_FAILED ├─ OK, the order still unpaid ─→ VERIFYING ─┬─ confirmed ─→ SUCCEEDED
 *                                                           ├─ OK, the order not payable ─→ FAILED      └─ refused ───→ FAILED
 *                                                           └─ the gateway says it failed → FAILED
 * INITIATED, REDIRECTED ── the order's time to pay ran out ──→ EXPIRED
 * ```
 *
 * Every other change is refused ([Failure.NotAllowed]). Its identity is [publicId]; [version] is the row's, for the
 * repository's check that nobody saved it in the meantime.
 *
 * @property amount what the attempt pays: the order's payable when the attempt was made ([PaymentPurpose.ORDER]), or what
 *   it owed after a change ([PaymentPurpose.BALANCE]). What the gateway is asked for and confirmed with.
 * @property purpose the order itself, or its balance due after a change request (phase 3).
 */
class Payment private constructor(
    val publicId: PaymentPublicId,
    val orderId: OrderId,
    val userId: UserId,
    val gateway: PaymentGatewayCode,
    val amount: Money,
    val purpose: PaymentPurpose,
    status: PaymentStatus,
    authority: GatewayAuthority?,
    redirect: PaymentRedirect?,
    refId: GatewayRefId?,
    cardMask: CardMask?,
    failureReason: PaymentFailureReason?,
    val createdOn: Instant,
    redirectedOn: Instant?,
    verifyingSince: Instant?,
    settledOn: Instant?,
    val version: Long,
) {
    var status: PaymentStatus = status
        private set

    /** The gateway's id of the session; from REDIRECTED on. */
    var authority: GatewayAuthority? = authority
        private set

    /** Where the shopper's browser goes to pay; from REDIRECTED on. */
    var redirect: PaymentRedirect? = redirect
        private set

    /** The bank's reference number; only once confirmed. */
    var refId: GatewayRefId? = refId
        private set
    var cardMask: CardMask? = cardMask
        private set

    /** Why it didn't succeed; null while it may still, and once it did. */
    var failureReason: PaymentFailureReason? = failureReason
        private set
    var redirectedOn: Instant? = redirectedOn
        private set

    /** When its confirming started: the reconciliation job tries again an attempt left VERIFYING too long. */
    var verifyingSince: Instant? = verifyingSince
        private set

    /** When it was settled, whichever way. */
    var settledOn: Instant? = settledOn
        private set

    init {
        require(amount > Money.ZERO) { "A payment of nothing: ${publicId.value}" }
        checkState()
    }

    // ============ the gateway's answer when the attempt started ============

    /** The gateway opened a session: the shopper goes to [redirect]. */
    fun redirected(authority: GatewayAuthority, redirect: PaymentRedirect, now: Instant): Result<Payment, Failure> =
        move(from = setOf(INITIATED), to = REDIRECTED) {
            this.authority = authority
            this.redirect = redirect
            redirectedOn = now
        }

    /**
     * The gateway refused to open a session, or didn't answer ([reason] GATEWAY_REJECTED or GATEWAY_UNREACHABLE). The
     * order is left as it is: the shopper did nothing wrong, and may try again.
     */
    fun startFailed(reason: PaymentFailureReason, now: Instant): Result<Payment, Failure> {
        require(reason == PaymentFailureReason.GATEWAY_REJECTED || reason == PaymentFailureReason.GATEWAY_UNREACHABLE) { "$reason is not why a start fails" }
        return move(from = setOf(INITIATED), to = START_FAILED) { settle(reason, now) }
    }

    // ============ the shopper back from the gateway ============

    /** The shopper cancelled on the gateway's page (a callback with NOK). */
    fun cancel(now: Instant): Result<Payment, Failure> =
        move(from = setOf(REDIRECTED), to = CANCELLED) { settle(PaymentFailureReason.USER_CANCELLED, now) }

    /** The shopper came back with OK and the order still waits for its money: it is confirmed next, outside any transaction. */
    fun startVerifying(now: Instant): Result<Payment, Failure> =
        move(from = setOf(REDIRECTED), to = VERIFYING) { verifyingSince = now }

    /**
     * The shopper came back with OK, but the order can't be paid any more (expired, or paid by another attempt). It is
     * not confirmed, so the gateway gives the money back by itself.
     */
    fun refuseAsNotPayable(now: Instant): Result<Payment, Failure> =
        move(from = setOf(REDIRECTED), to = FAILED) { settle(PaymentFailureReason.ORDER_NOT_PAYABLE, now) }

    // ============ confirming ============

    /** The gateway confirmed the payment («already confirmed» counts too). */
    fun succeed(refId: GatewayRefId, cardMask: CardMask?, now: Instant): Result<Payment, Failure> =
        move(from = setOf(VERIFYING), to = SUCCEEDED) {
            this.refId = refId
            this.cardMask = cardMask
            settledOn = now
        }

    /**
     * The gateway says the payment didn't go through: refused while confirming it (GATEWAY_DECLINED, or
     * GATEWAY_REJECTED when our request was wrong), or, asked by the reconciliation job about an attempt the shopper
     * never came back from, that it failed (GATEWAY_DECLINED).
     */
    fun fail(reason: PaymentFailureReason, now: Instant): Result<Payment, Failure> {
        require(reason == PaymentFailureReason.GATEWAY_DECLINED || reason == PaymentFailureReason.GATEWAY_REJECTED) { "$reason is not how a gateway fails a payment" }
        val from = if (reason == PaymentFailureReason.GATEWAY_DECLINED) setOf(REDIRECTED, VERIFYING) else setOf(VERIFYING)
        return move(from = from, to = FAILED) { settle(reason, now) }
    }

    /**
     * The money went back to the card: given back by the gateway right after (the exception of section 10.5), or, in a
     * later phase, refunded. Its reference number stays: the shopper's bank knows the payment by it.
     */
    fun refund(now: Instant): Result<Payment, Failure> = move(from = setOf(SUCCEEDED), to = REFUNDED) { }

    // ============ the order's time ============

    /**
     * The attempt's time is over while it was still open: its order's time to pay ran out, or its shopper left its
     * session on the gateway's page (abandoned, PaymentPolicy.isAbandoned). One being confirmed is never expired.
     */
    fun expire(now: Instant): Result<Payment, Failure> =
        move(from = setOf(INITIATED, REDIRECTED), to = EXPIRED) { settle(PaymentFailureReason.EXPIRED, now) }

    // ============ inside ============

    private fun move(from: Set<PaymentStatus>, to: PaymentStatus, change: () -> Unit): Result<Payment, Failure> {
        if (status !in from) return Result.failure(Failure.NotAllowed(status, to))
        change()
        status = to
        checkState()
        return Result.success(this)
    }

    private fun settle(reason: PaymentFailureReason, now: Instant) {
        failureReason = reason
        settledOn = now
    }

    // The rules of the table's CHECKs, so a broken row or a bug stops here first.
    private fun checkState() {
        require(status !in setOf(REDIRECTED, VERIFYING, SUCCEEDED, REFUNDED) || (authority != null && redirect != null)) {
            "Payment ${publicId.value} is $status without the gateway's session"
        }
        require((status == SUCCEEDED || status == REFUNDED) == (refId != null)) { "Payment ${publicId.value} is $status with refId=${refId != null}" }
        require((status in FAILURES) == (failureReason != null)) { "Payment ${publicId.value} is $status with failureReason=$failureReason" }
        require(status.isSettled == (settledOn != null)) { "Payment ${publicId.value} is $status with settledOn=$settledOn" }
    }

    override fun equals(other: Any?): Boolean = other is Payment && other.publicId == publicId

    override fun hashCode(): Int = publicId.hashCode()

    override fun toString(): String = "Payment(publicId=${publicId.value}, orderId=${orderId.value}, status=$status)"

    companion object {
        private val FAILURES = setOf(START_FAILED, CANCELLED, FAILED, EXPIRED)

        /**
         * A new attempt for [amount]: the order's payable now, or ([purpose] BALANCE) what it owes after a change. The
         * gateway is asked next, outside the transaction.
         */
        fun start(
            publicId: PaymentPublicId,
            orderId: OrderId,
            userId: UserId,
            gateway: PaymentGatewayCode,
            amount: Money,
            now: Instant,
            purpose: PaymentPurpose = PaymentPurpose.ORDER,
        ): Payment = Payment(publicId, orderId, userId, gateway, amount, purpose, INITIATED, null, null, null, null, null, now, null, null, null, version = 0)

        fun constructFromPersisted(
            publicId: PaymentPublicId,
            orderId: OrderId,
            userId: UserId,
            gateway: PaymentGatewayCode,
            amount: Money,
            status: PaymentStatus,
            authority: GatewayAuthority?,
            redirect: PaymentRedirect?,
            refId: GatewayRefId?,
            cardMask: CardMask?,
            failureReason: PaymentFailureReason?,
            createdOn: Instant,
            redirectedOn: Instant?,
            verifyingSince: Instant?,
            settledOn: Instant?,
            version: Long,
            purpose: PaymentPurpose = PaymentPurpose.ORDER,
        ): Payment = Payment(
            publicId, orderId, userId, gateway, amount, purpose, status, authority, redirect, refId, cardMask, failureReason, createdOn, redirectedOn,
            verifyingSince, settledOn, version,
        )
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Not a change this attempt can make from where it is: a callback that came late, or a bug. */
        data class NotAllowed(val from: PaymentStatus, val to: PaymentStatus) :
            Failure("PAYMENT_TRANSITION_INVALID", FailureKind.CONFLICT, mapOf("from" to from.name, "to" to to.name))
    }
}
