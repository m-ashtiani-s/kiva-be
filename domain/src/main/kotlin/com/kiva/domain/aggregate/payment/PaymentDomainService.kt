package com.kiva.domain.aggregate.payment

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import java.time.Instant

/**
 * The rules between an attempt to pay and its order (roadmap sections 3, row 3, and 10.6): what a shopper back from the
 * gateway changes, when an attempt may be confirmed, and what the gateway's answer to confirming it does to both.
 *
 * **An attempt is confirmed only while its order still waits for its money, and while no other attempt of the order is
 * being confirmed.** Both are checked on every attempt of the order, which the caller has locked together, so two
 * attempts that came back paid at the same moment never both reach the gateway's verify: the money of the second is
 * never taken, and the gateway gives it back by itself. Taking it and giving it back later would need a refund that
 * can fail; never taking it can't.
 *
 * It changes the payment and the order it is given and never saves them; the use case saves both in its transaction.
 */
class PaymentDomainService {

    private val logger = getLogger()

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
