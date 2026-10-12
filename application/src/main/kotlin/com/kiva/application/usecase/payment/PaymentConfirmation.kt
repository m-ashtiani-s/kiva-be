package com.kiva.application.usecase.payment

import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayReceipt
import com.kiva.application.port.PaymentGateways
import com.kiva.application.port.SmsSender
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.PaymentDomainService
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Confirming an attempt with its gateway and settling it (roadmap section 10.6, steps 3 to 5): what the callback does
 * once the shopper came back paid, and what the reconciliation job does for an attempt left being confirmed or never
 * come back from (step 16). One place, so both settle an attempt the same way (Rule 15).
 *
 * 1. Outside any transaction: the gateway confirms the session, with the amount **we** stored for the attempt.
 * 2. [tx] Every attempt of the order is locked, and the attempt must still be VERIFYING: whoever settled it first
 *    (a callback, or the job) wins, and this answer is dropped. Then PaymentDomainService settles it: confirmed, it
 *    succeeds and the order is paid; refused, it fails and so does the order's try; no answer, it stays VERIFYING for
 *    the job to try again. The exchange goes into the payment log.
 * 3. After the commit, a paid order is told «پرداخت موفق». A failed SMS is only logged: the order is paid all the same.
 * 4. The exception of section 10.5: confirmed for an order that couldn't take it (a bug the rules keep from happening).
 *    The money is ours, so the attempt SUCCEEDED and an ERROR says so; then, while the gateway still allows it, the
 *    money is given back at once (reverse, outside any transaction), and the attempt is REFUNDED. Otherwise, and when
 *    the reverse is refused, support gives it back by hand.
 */
class PaymentConfirmation(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val paymentDomainService: PaymentDomainService,
    private val paymentGateways: PaymentGateways,
    private val smsSender: SmsSender,
    private val clock: Clock,
) {

    private val logger = getLogger()

    /** How the gateway answered, or that no adapter could be asked. */
    private sealed interface Answer {
        data class Confirmed(val receipt: GatewayReceipt) : Answer

        data class Refused(val failure: GatewayFailure) : Answer

        /** The attempt's gateway has no adapter any more (the configuration changed since it started). */
        data object NoAdapter : Answer
    }

    /** What settling did, the number to tell «پرداخت موفق» when the order was paid by it, and whether to give it back. */
    private class Settled(val payment: Payment, val paidTo: IranianMobileNumber?, val reverse: Boolean = false)

    /**
     * Confirms [payment], which was VERIFYING when read, and settles it. [by] is who the order's events are of: the
     * shopper for a callback, the system for the job. Returns the attempt as it stands after.
     */
    suspend fun confirm(payment: Payment, by: UserId): Payment {
        check(payment.status == PaymentStatus.VERIFYING) { "Payment ${payment.publicId.value} is ${payment.status}, not being confirmed" }
        val authority = checkNotNull(payment.authority) { "Payment ${payment.publicId.value} is being confirmed without its session" }
        val orderCode = OrderCodePolicy.codeOf(payment.orderId)

        val started = TimeSource.Monotonic.markNow()
        val answer = paymentGateways.of(payment.gateway)
            ?.verify(authority, payment.amount)
            ?.let { result -> result.getOrNull()?.let(Answer::Confirmed) ?: Answer.Refused(checkNotNull(result.failureOrNull())) }
            ?: Answer.NoAdapter
        val took = started.elapsedNow().toJavaDuration()

        val settled = transactionManager.inTransaction { Result.success(settle(payment, orderCode, answer, took, by)) }.unwrapOr { it }
        settled.paidTo?.let { sendPaid(it, orderCode) }
        return if (settled.reverse) reverse(settled.payment, orderCode) else settled.payment
    }

    private fun settle(read: Payment, orderCode: OrderCode, answer: Answer, took: Duration, by: UserId): Settled {
        val payment = paymentRepository.lockAttemptsOf(read.orderId).firstOrNull { it.publicId == read.publicId }
            ?: error("Payment ${read.publicId.value} was read a moment ago, yet it isn't there")
        if (payment.status != PaymentStatus.VERIFYING) {
            logger.info("PaymentConfirmation: payment ${payment.publicId.value} was settled by another request meanwhile (${payment.status}); this answer is dropped")
            return Settled(payment, paidTo = null)
        }
        val order = orderRepository.get(payment.orderId) ?: error("Payment ${payment.publicId.value} is of order ${payment.orderId.value}, which has no events")
        val now = clock.instant()

        var reverse = false
        val entry = when (answer) {
            is Answer.Confirmed -> {
                when (val confirmed = paymentDomainService.confirmed(payment, order, answer.receipt.refId, answer.receipt.cardMask, by, now)) {
                    PaymentDomainService.Confirmed.Paid -> Unit
                    is PaymentDomainService.Confirmed.OrderNotPaid -> {
                        reverse = paymentDomainService.mayReverse(payment, order, now)
                        logger.error(
                            "PAID_BUT_ORDER_NOT_PAYABLE: payment ${payment.publicId.value} of order ${orderCode.value} was confirmed for ${payment.amount.toman} " +
                                "but the order refused it (${confirmed.failure.code}, ${order.status}); " +
                                if (reverse) "the gateway is asked to give it back" else "the money is to be given back by hand",
                        )
                    }
                }
                PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, if (answer.receipt.alreadyConfirmed) "ALREADY_CONFIRMED" else "OK", now, duration = took)
            }
            is Answer.Refused -> {
                refuse(payment, order, answer.failure, by, now)
                PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, answer.failure.logOutcome, now, answer.failure.gatewayCode, duration = took)
            }
            Answer.NoAdapter -> {
                logger.error("PaymentConfirmation: no adapter for gateway ${payment.gateway} of payment ${payment.publicId.value}; the configuration is wrong")
                PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, "NO_ADAPTER", now)
            }
        }
        orderRepository.save(order)
        if (payment.status != PaymentStatus.VERIFYING) paymentRepository.save(payment)
        paymentRepository.appendLog(payment.publicId, entry)
        logger.info("PaymentConfirmation: payment ${payment.publicId.value} of order ${orderCode.value} is ${payment.status}, the order ${order.status}")

        val paidTo = if (payment.status == PaymentStatus.SUCCEEDED && order.paidByPaymentId == payment.publicId) {
            (userRepository.findById(order.userId) ?: error("Order ${orderCode.value} is of user ${order.userId.value}, who isn't there")).phone
        } else {
            null
        }
        return Settled(payment, paidTo, reverse)
    }

    /** Step 4: the gateway gives the money back, outside any transaction; then the attempt is REFUNDED. */
    private suspend fun reverse(payment: Payment, orderCode: OrderCode): Payment {
        val authority = checkNotNull(payment.authority)
        val started = TimeSource.Monotonic.markNow()
        val answer = paymentGateways.of(payment.gateway)?.reverse(authority)
        val took = started.elapsedNow().toJavaDuration()

        return transactionManager.inTransaction {
            val current = paymentRepository.lockAttemptsOf(payment.orderId).first { it.publicId == payment.publicId }
            val now = clock.instant()
            val failure = answer?.failureOrNull()
            if (answer != null && failure == null && current.status == PaymentStatus.SUCCEEDED) {
                val order = orderRepository.get(current.orderId) ?: error("Order ${orderCode.value} has no events")
                paymentDomainService.reversed(current, order, now)
                paymentRepository.save(current)
                logger.warn("PaymentConfirmation: payment ${current.publicId.value} of order ${orderCode.value} was given back by the gateway; it is REFUNDED")
            } else {
                logger.error("PAID_BUT_ORDER_NOT_PAYABLE: the gateway didn't give payment ${current.publicId.value} of order ${orderCode.value} back (${failure?.code ?: "no adapter"}); support gives it back by hand")
            }
            val entry = PaymentLogEntry(PaymentLogEntry.Kind.REVERSE, if (answer != null && failure == null) "OK" else failure?.logOutcome ?: "NO_ADAPTER", now, failure?.gatewayCode, duration = took)
            paymentRepository.appendLog(current.publicId, entry)
            Result.success(current)
        }.unwrapOr { it }
    }

    /** No answer leaves the attempt being confirmed, for the job to ask again; any other refusal fails it. */
    private fun refuse(payment: Payment, order: Order, failure: GatewayFailure, by: UserId, now: Instant) {
        val reason = when (failure) {
            GatewayFailure.Unreachable -> null.also {
                logger.warn("PaymentConfirmation: the gateway didn't answer the confirmation of payment ${payment.publicId.value}; it stays VERIFYING")
            }
            is GatewayFailure.Declined -> PaymentFailureReason.GATEWAY_DECLINED
            is GatewayFailure.Rejected -> PaymentFailureReason.GATEWAY_REJECTED.also {
                logger.error("PaymentConfirmation: gateway ${payment.gateway} refused our confirmation of payment ${payment.publicId.value} (${failure.gatewayCode}); the configuration is wrong")
            }
            GatewayFailure.AmountMismatch -> PaymentFailureReason.GATEWAY_REJECTED.also {
                logger.error("PaymentConfirmation: AMOUNT_MISMATCH, gateway ${payment.gateway} says payment ${payment.publicId.value} wasn't paid ${payment.amount.toman}; never a payment")
            }
        } ?: return
        paymentDomainService.refused(payment, order, reason, by, now)
    }

    /** «پرداخت موفق». A failed SMS is only logged: the order is paid all the same. */
    private suspend fun sendPaid(to: IranianMobileNumber, orderCode: OrderCode) {
        try {
            smsSender.sendOrderPaid(to, orderCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("PaymentConfirmation: the «پرداخت موفق» SMS of order ${orderCode.value} to ${to.masked()} failed; the order is paid all the same", e)
        }
    }
}
