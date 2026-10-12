package com.kiva.domain.aggregate.payment

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.policy.PaymentPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentIdGenerator
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import java.time.Instant

/**
 * The rules between an attempt to pay and its order (roadmap sections 3, rows 3 and 4, 10.6, 10.8 and 15): when a new
 * attempt may start, what a shopper back from the gateway changes, when an attempt may be confirmed, what the gateway's
 * answer to confirming it does to both, and when an unpaid order may expire.
 *
 * **An attempt is confirmed only while its order still waits for its money, and while no other attempt of the order is
 * being confirmed.** Both are checked on every attempt of the order, which the caller has locked together, so two
 * attempts that came back paid at the same moment never both reach the gateway's verify: the money of the second is
 * never taken, and the gateway gives it back by itself. Taking it and giving it back later would need a refund that
 * can fail; never taking it can't.
 *
 * It changes the payment and the order it is given and never saves them; the use case saves both in its transaction.
 */
class PaymentDomainService(private val paymentIdGenerator: PaymentIdGenerator) {

    private val logger = getLogger()

    /**
     * Another attempt to pay [order] (`POST /payments/{paymentId}/retry`, `POST /me/orders/{orderCode}/payments`), for its
     * whole payable, through [gateway]. The rules, in the order the customer is told them:
     *
     * 1. the order isn't paid already (ORDER_ALREADY_PAID);
     * 2. its time to pay hasn't ended, and it hasn't expired (PAYMENT_EXPIRED): the time is never moved, so a robot can't
     *    hold the stock by asking again and again;
     * 3. the gateway is available now ([gatewayAvailable]: switched on and with an adapter; PAYMENT_GATEWAY_UNAVAILABLE);
     * 4. the order has had fewer than PaymentPolicy.MAX_ATTEMPTS_PER_ORDER attempts (429, until its time to pay ends).
     *
     * Only then is the attempt's id taken. The earlier attempts stay as they are: one still open in another tab that is
     * paid first wins, and the rules of [beginConfirming] keep the order from being paid twice. [attempts] are every
     * attempt of the order, locked by the caller.
     */
    fun startAttempt(
        order: Order,
        attempts: List<Payment>,
        gateway: PaymentGatewayCode,
        gatewayAvailable: Boolean,
        by: UserId,
        now: Instant,
    ): Result<Payment, StartAttemptFailure> {
        logger.info("startAttempt order=${order.code.value} gateway=$gateway attempts=${attempts.size}")
        require(attempts.all { it.orderId == order.id }) { "The attempts are not those of order ${order.id.value}" }
        if (order.status.isPaid) return Result.failure(StartAttemptFailure.AlreadyPaid)
        if (!order.canStartPayment(now)) return Result.failure(StartAttemptFailure.Expired)
        if (!gatewayAvailable) return Result.failure(StartAttemptFailure.GatewayUnavailable)
        PaymentPolicy.refusalToAttempt(attempts.size, order.holdUntil, now)?.let { return Result.failure(StartAttemptFailure.TooManyAttempts(it)) }

        return Result.success(Payment.start(paymentIdGenerator.nextPaymentPublicId(), order.id, by, gateway, order.totals.payable, now))
    }

    /**
     * The shopper came back from the gateway: [paid] is what the gateway's callback said (Zarinpal's OK or NOK).
     * [attempts] are every attempt of the order, [payment] among them, all locked by the caller.
     *
     * A callback that comes again (a refresh, or GET and POST together) finds its attempt moved on and changes nothing.
     */
    fun shopperReturned(payment: Payment, paid: Boolean, order: Order, attempts: List<Payment>, by: UserId, now: Instant): Return {
        logger.info("shopperReturned payment=${payment.publicId.value} order=${order.code.value} paid=$paid status=${payment.status}")
        requireSameOrder(payment, order, attempts)
        return when {
            payment.status.isSettled -> Return.AlreadySettled
            payment.status == PaymentStatus.VERIFYING -> Return.BeingConfirmed
            // A session is opened before the shopper can come back, so an attempt found by its session has left INITIATED.
            payment.status != PaymentStatus.REDIRECTED -> error("Payment ${payment.publicId.value} has a session yet is ${payment.status}")
            !paid -> {
                payment.cancel(now).orBug()
                // Changes nothing on an order that was paid by another attempt or expired meanwhile.
                order.recordPaymentFailure(payment.publicId, PaymentFailureReason.USER_CANCELLED, by, now)
                Return.Cancelled
            }
            else -> beginConfirming(payment, order, attempts, now)
        }
    }

    /**
     * An attempt the gateway says was paid may be confirmed now: it is REDIRECTED, its order still waits for its money,
     * and no other attempt of the order is being confirmed. The callback asks this, and so does the reconciliation job
     * for an attempt the shopper never came back from. [attempts] as in [shopperReturned].
     *
     * - The order no longer waits (expired, or paid by another attempt): the attempt fails as ORDER_NOT_PAYABLE without
     *   being confirmed, so its money is never taken.
     * - Another attempt is being confirmed: nothing changes yet. If that one is confirmed, this one is later refused as
     *   above; if not, this one is confirmed later, by the reconciliation job.
     */
    fun beginConfirming(payment: Payment, order: Order, attempts: List<Payment>, now: Instant): Return {
        requireSameOrder(payment, order, attempts)
        check(payment.status == PaymentStatus.REDIRECTED) { "Only an attempt with an open session may be confirmed, not ${payment.status}" }
        if (!order.isAwaitingPayment) {
            payment.refuseAsNotPayable(now).orBug()
            return Return.NotPayable
        }
        val other = attempts.firstOrNull { it != payment && it.status == PaymentStatus.VERIFYING }
        if (other != null) {
            logger.info("beginConfirming: payment ${payment.publicId.value} waits, ${other.publicId.value} of the same order is being confirmed")
            return Return.Deferred
        }
        payment.startVerifying(now).orBug()
        return Return.Confirming
    }

    /**
     * The gateway confirmed the attempt being confirmed: it succeeds, and the order is paid with it. The gateway has the
     * money, so the attempt succeeds even when the order can't take it any more; that is a bug (the rules above keep it
     * from happening), told back as [Confirmed.OrderNotPaid] for the caller to log loudly and support to give back.
     */
    fun confirmed(payment: Payment, order: Order, refId: GatewayRefId, cardMask: CardMask?, by: UserId, now: Instant): Confirmed {
        requireSameOrder(payment, order, listOf(payment))
        payment.succeed(refId, cardMask, now).orBug()
        // Not held for «رزرو ۴ روزه» until round 2 (step 19) decides it here.
        return when (val paid = order.markPaid(payment.publicId, payment.amount, heldForReservation = false, by, now)) {
            is Result.Success -> Confirmed.Paid
            is Result.Failure -> Confirmed.OrderNotPaid(paid.failure)
        }
    }

    /**
     * Whether a confirmed attempt is to be given back at once: its order didn't take it (Confirmed.OrderNotPaid), and the
     * gateway still reverses it (PaymentPolicy.REVERSE_WINDOW from its confirmation). Otherwise support gives it back.
     */
    fun mayReverse(payment: Payment, order: Order, now: Instant): Boolean =
        payment.status == PaymentStatus.SUCCEEDED && order.paidByPaymentId != payment.publicId &&
            now.isBefore(checkNotNull(payment.settledOn).plus(PaymentPolicy.REVERSE_WINDOW))

    /**
     * The gateway gave a confirmed attempt's money back ([mayReverse]): it is REFUNDED. Never the attempt that paid its
     * order: that one is refunded only with its order (a later phase).
     */
    fun reversed(payment: Payment, order: Order, now: Instant) {
        requireSameOrder(payment, order, listOf(payment))
        require(order.paidByPaymentId != payment.publicId) { "Payment ${payment.publicId.value} paid order ${order.code.value}; it isn't reversed alone" }
        payment.refund(now).orBug()
    }

    /**
     * The gateway didn't take the payment: it refused to confirm it ([reason] GATEWAY_DECLINED, or GATEWAY_REJECTED when
     * our request was wrong), or, asked about an attempt the shopper never came back from, says it failed. The attempt
     * fails, and so does the order's try, while it still waits.
     */
    fun refused(payment: Payment, order: Order, reason: PaymentFailureReason, by: UserId, now: Instant) {
        requireSameOrder(payment, order, listOf(payment))
        payment.fail(reason, now).orBug()
        order.recordPaymentFailure(payment.publicId, reason, by, now)
    }

    private fun requireSameOrder(payment: Payment, order: Order, attempts: List<Payment>) {
        require(payment.orderId == order.id) { "Payment ${payment.publicId.value} is not of order ${order.id.value}" }
        require(attempts.all { it.orderId == order.id } && payment in attempts) { "The attempts are not those of order ${order.id.value}" }
    }

    // Every change here is made only from the state checked just before it.
    private fun Result<Payment, Payment.Failure>.orBug(): Payment =
        getOrNull() ?: error("A payment change checked beforehand was refused: ${failureOrNull()?.code}")

    /**
     * The job's expiry of an unpaid order whose time to pay is over (roadmap section 15): it expires, and so does every
     * attempt of it still open. Returns those attempts, to be saved; the order's stock is given back by the caller.
     *
     * Not while one of its attempts may still pay it: one being confirmed, or one whose shopper may still be on the
     * bank's page (sent there less than PaymentPolicy.GATEWAY_WINDOW ago). Otherwise a shopper who paid at the last
     * minute would come back to an order that let its bag go. [attempts] are every attempt of the order, locked by the
     * caller, so a callback can't start confirming one in between.
     */
    fun expireUnpaid(order: Order, attempts: List<Payment>, by: UserId, now: Instant): Result<List<Payment>, ExpireRefusal> {
        logger.info("expireUnpaid order=${order.code.value} status=${order.status} attempts=${attempts.map { it.status }}")
        require(attempts.all { it.orderId == order.id }) { "The attempts are not those of order ${order.id.value}" }
        if (!order.isAwaitingPayment) return Result.failure(ExpireRefusal.NotAwaitingPayment(order.status))
        if (now.isBefore(order.holdUntil)) return Result.failure(ExpireRefusal.TooEarly(order.holdUntil))
        val inProgress = attempts.firstOrNull {
            it.status == PaymentStatus.VERIFYING || (it.status == PaymentStatus.REDIRECTED && !PaymentPolicy.isAbandoned(checkNotNull(it.redirectedOn), now))
        }
        if (inProgress != null) return Result.failure(ExpireRefusal.PaymentInProgress(inProgress.publicId))

        order.expire(by, now).getOrNull() ?: error("Order ${order.code.value} awaiting payment past its time refused to expire")
        val open = attempts.filter { it.status == PaymentStatus.INITIATED || it.status == PaymentStatus.REDIRECTED }
        open.forEach { it.expire(now).orBug() }
        return Result.success(open)
    }

    /**
     * The gateway says an attempt the shopper never came back from isn't paid, and it is past the window a shopper may
     * still be on the bank's page (PaymentPolicy.isAbandoned): it is closed as EXPIRED, so it is never asked about
     * again. Its order is untouched; whether that one expires is [expireUnpaid]'s. True when it was closed.
     */
    fun abandon(payment: Payment, now: Instant): Boolean {
        if (payment.status != PaymentStatus.REDIRECTED || !PaymentPolicy.isAbandoned(checkNotNull(payment.redirectedOn), now)) return false
        payment.expire(now).orBug()
        return true
    }

    sealed class ExpireRefusal(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.CONFLICT, meta = meta) {
        /** Paid, or expired already: nothing to expire. */
        data class NotAwaitingPayment(val status: OrderStatus) : ExpireRefusal("ORDER_NOT_AWAITING_PAYMENT", mapOf("status" to status.name))

        data class TooEarly(val holdUntil: Instant) : ExpireRefusal("ORDER_NOT_DUE", mapOf("holdUntil" to holdUntil.toString()))

        /** An attempt is being confirmed, or its shopper may still be on the bank's page: the next run looks again. */
        data class PaymentInProgress(val paymentId: PaymentPublicId) : ExpireRefusal("PAYMENT_IN_PROGRESS", mapOf("paymentId" to paymentId.value))
    }

    sealed class StartAttemptFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object AlreadyPaid : StartAttemptFailure("ORDER_ALREADY_PAID", FailureKind.BUSINESS_RULE)

        /** Its time to pay ended: its stock is given back, or about to be. Only «خرید دوباره» is left. */
        data object Expired : StartAttemptFailure("PAYMENT_EXPIRED", FailureKind.BUSINESS_RULE)

        data object GatewayUnavailable : StartAttemptFailure("PAYMENT_GATEWAY_UNAVAILABLE", FailureKind.BUSINESS_RULE)

        /** 429, with how long until the order's time to pay ends (`retryAfterSeconds`). */
        data class TooManyAttempts(val refusal: PaymentPolicy.Refusal.TooManyAttempts) :
            StartAttemptFailure(refusal.code, refusal.kind, refusal.meta)
    }

    /** What a shopper back from the gateway, or a paid attempt found by the reconciliation job, changed. */
    sealed interface Return {
        /** The attempt had ended already: nothing changed. */
        data object AlreadySettled : Return

        /** It is being confirmed already (by an earlier callback, or the job): nothing changed. */
        data object BeingConfirmed : Return

        /** The shopper cancelled: the attempt is CANCELLED and the order's try failed. */
        data object Cancelled : Return

        /** Paid, but the order no longer waits: the attempt failed as ORDER_NOT_PAYABLE, never confirmed. */
        data object NotPayable : Return

        /** Paid, but another attempt of the order is being confirmed: nothing changed yet. */
        data object Deferred : Return

        /** Paid, and the order waits: the attempt is VERIFYING; the gateway confirms it next, outside the transaction. */
        data object Confirming : Return
    }

    /** What a confirmed attempt did to its order. */
    sealed interface Confirmed {
        data object Paid : Confirmed

        /** The money came in, but the order couldn't take it: a bug, to be logged as PAID_BUT_ORDER_NOT_PAYABLE. */
        data class OrderNotPaid(val failure: Order.MarkPaidFailure) : Confirmed
    }
}
