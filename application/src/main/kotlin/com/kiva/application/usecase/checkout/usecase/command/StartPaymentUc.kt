package com.kiva.application.usecase.checkout.usecase.command

import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayPaymentRequest
import com.kiva.application.port.GatewayRedirect
import com.kiva.application.port.PaymentGateways
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import java.time.Clock
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Opens the gateway's session of an attempt to pay (roadmap section 10.5): after an order is placed, and for every new
 * attempt later. With the callback, the only use case with two transactions, and the gateway called **between** them,
 * never inside one: a slow gateway must not hold a database connection, and the attempt's state machine makes every
 * half-done step safe to pick up again.
 *
 * 1. [tx] the attempt is read. Only one still INITIATED goes to the gateway. One that was opened already (a repeat of
 *    the same request) answers as it went: its session, or the same PAYMENT_GATEWAY_UNAVAILABLE;
 * 2. the gateway is asked for a session, for the amount the attempt stored;
 * 3. [tx] the attempt is locked and moves to REDIRECTED, or to START_FAILED when the gateway refused or didn't answer:
 *    the order stays unpaid with its stock held, and the shopper goes on with `POST /payments/{paymentId}/retry`. If
 *    another request finished the attempt meanwhile, its outcome stands, and this session is dropped: never paid, it
 *    ends at the gateway by itself. The exchange goes into the payment log.
 */
class StartPaymentUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val paymentGateways: PaymentGateways,
    private val clock: Clock,
) : CommandUseCase<StartPaymentUc.Cmd, PaymentPublicId, StartPaymentUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token: someone else's attempt is PAYMENT_NOT_FOUND. */
    data class Cmd(val userId: UserId, val paymentId: PaymentPublicId)

    /** What the gateway said, or that no adapter could be asked. */
    private sealed interface Answer {
        data class Opened(val session: GatewayRedirect) : Answer

        data class Refused(val failure: GatewayFailure) : Answer

        /** The settings name a gateway this app has no adapter for any more (changed since the order was placed). */
        data object NoAdapter : Answer
    }

    override suspend fun execute(command: Cmd): Result<PaymentPublicId, Failure> {
        logger.info("StartPaymentUc.execute userId=${command.userId.value} paymentId=${command.paymentId.value}")

        val payment = transactionManager.inTransaction {
            val payment = paymentRepository.find(command.paymentId)?.takeIf { it.userId == command.userId }
                ?: return@inTransaction Result.failure(Failure.PaymentNotFound)
            Result.success(payment)
        }.unwrapOr { return Result.failure(it) }
        if (payment.status != PaymentStatus.INITIATED) return outcomeOf(payment)

        val orderCode = OrderCodePolicy.codeOf(payment.orderId)
        val started = TimeSource.Monotonic.markNow()
        val answer = paymentGateways.of(payment.gateway)
            ?.requestPayment(GatewayPaymentRequest(payment.publicId, orderCode, payment.amount))
            ?.let { result -> result.getOrNull()?.let(Answer::Opened) ?: Answer.Refused(checkNotNull(result.failureOrNull())) }
            ?: Answer.NoAdapter
        val took = started.elapsedNow().toJavaDuration()

        // The attempt's new state commits whatever the answer: a failure returned inside the transaction would roll it back.
        val settled = transactionManager.inTransaction {
            val current = paymentRepository.lock(command.paymentId) ?: error("Payment ${command.paymentId.value} was read a moment ago, yet it isn't there")
            if (current.status != PaymentStatus.INITIATED) {
                logger.info("StartPaymentUc: payment ${current.publicId.value} was started by another request meanwhile (${current.status}); this session is dropped")
                return@inTransaction Result.success(current)
            }
            val now = clock.instant()
            val entry = when (answer) {
                is Answer.Opened -> {
                    current.redirected(answer.session.authority, answer.session.redirect, now).unwrapOr { error("INITIATED can't be redirected: ${it.code}") }
                    PaymentLogEntry(PaymentLogEntry.Kind.REQUEST, "OK", now, duration = took)
                }
                is Answer.Refused -> {
                    current.startFailed(reasonOf(answer.failure, current), now).unwrapOr { error("INITIATED can't fail to start: ${it.code}") }
                    PaymentLogEntry(PaymentLogEntry.Kind.REQUEST, outcomeCodeOf(answer.failure), now, gatewayCodeOf(answer.failure), duration = took)
                }
                Answer.NoAdapter -> {
                    logger.error("StartPaymentUc: no adapter for gateway ${current.gateway} of payment ${current.publicId.value}; the configuration is wrong")
                    current.startFailed(PaymentFailureReason.GATEWAY_REJECTED, now).unwrapOr { error("INITIATED can't fail to start: ${it.code}") }
                    PaymentLogEntry(PaymentLogEntry.Kind.REQUEST, "NO_ADAPTER", now)
                }
            }
            paymentRepository.save(current)
            paymentRepository.appendLog(current.publicId, entry)
            logger.info("StartPaymentUc: payment ${current.publicId.value} of order ${orderCode.value} is ${current.status}")
            Result.success(current)
        }.unwrapOr { it }
        return outcomeOf(settled)
    }

    /**
     * How an attempt that left INITIATED went: one with a session opened is started, whatever happened to it since
     * (the order's answer shows how it stands now); one that never got a session (refused, or expired before) is
     * PAYMENT_GATEWAY_UNAVAILABLE, with the order and the attempt to go on from.
     */
    private fun outcomeOf(payment: Payment): Result<PaymentPublicId, Failure> =
        if (payment.redirect != null) {
            Result.success(payment.publicId)
        } else {
            Result.failure(Failure.GatewayUnavailable(OrderCodePolicy.codeOf(payment.orderId), payment.publicId))
        }

    /** No answer is unknown, so it may be tried again; any refusal of a request to open a session is our configuration's. */
    private fun reasonOf(failure: GatewayFailure, payment: Payment): PaymentFailureReason = when (failure) {
        GatewayFailure.Unreachable -> PaymentFailureReason.GATEWAY_UNREACHABLE
        else -> {
            logger.error("StartPaymentUc: gateway ${payment.gateway} refused to open a session for payment ${payment.publicId.value}: ${failure.code}")
            PaymentFailureReason.GATEWAY_REJECTED
        }
    }

    private fun outcomeCodeOf(failure: GatewayFailure): String = when (failure) {
        GatewayFailure.Unreachable -> "UNREACHABLE"
        is GatewayFailure.Rejected -> "REJECTED"
        is GatewayFailure.Declined -> "DECLINED"
        GatewayFailure.AmountMismatch -> "AMOUNT_MISMATCH"
    }

    private fun gatewayCodeOf(failure: GatewayFailure): Int? = when (failure) {
        is GatewayFailure.Rejected -> failure.gatewayCode
        is GatewayFailure.Declined -> failure.gatewayCode
        else -> null
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object PaymentNotFound : Failure("PAYMENT_NOT_FOUND", FailureKind.NOT_FOUND)

        /**
         * The gateway refused or didn't answer, **after** the order was placed (the contract's second case of this code):
         * the order exists and holds its stock, so the answer names it and its attempt, to go on with a retry.
         */
        data class GatewayUnavailable(val orderCode: OrderCode, val paymentId: PaymentPublicId) :
            Failure("PAYMENT_GATEWAY_UNAVAILABLE", FailureKind.BUSINESS_RULE, mapOf("orderCode" to orderCode.value, "paymentId" to paymentId.value))
    }
}
