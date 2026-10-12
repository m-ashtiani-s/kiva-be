package com.kiva.application.usecase.payment.usecase.command

import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayInquiry
import com.kiva.application.port.PaymentGateways
import com.kiva.application.usecase.payment.PaymentConfirmation
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.PaymentDomainService
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import java.time.Clock
import java.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * Settles one attempt the shopper's callback didn't (the reconciliation job, roadmap section 15), so a payment taken by
 * the bank never stays unknown to the shop (section 3, row 20):
 *
 * - **being confirmed** (the gateway didn't answer, or the app stopped halfway): confirmed again by
 *   PaymentConfirmation. «Already confirmed» counts as confirmed, so asking twice is harmless.
 * - **sent to the gateway, the shopper never back**: the gateway is asked how the session stands, outside any
 *   transaction. Paid: confirmed, by the same rules as a callback (only while the order waits, one attempt of an order at
 *   a time). Failed: the attempt fails. Still at the bank: left alone, unless it is abandoned (past
 *   PaymentPolicy.GATEWAY_WINDOW), then it is closed as EXPIRED and never asked about again. No answer: asked again next
 *   run.
 *
 * Whatever it finds is settled with the order's attempts locked, so a callback or another instance of the job at the
 * same moment settles it once. It never fails: every outcome is logged, and the next run looks again.
 */
class ReconcilePaymentUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val paymentDomainService: PaymentDomainService,
    private val paymentGateways: PaymentGateways,
    private val paymentConfirmation: PaymentConfirmation,
    private val clock: Clock,
) : CommandUseCase<ReconcilePaymentUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    data class Cmd(val paymentId: PaymentPublicId)

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("ReconcilePaymentUc.execute paymentId=${command.paymentId.value}")
        val payment = transactionManager.inTransaction { Result.success(paymentRepository.find(command.paymentId)) }.unwrapOr { it }
            ?: return Result.success(Unit).also { logger.warn("ReconcilePaymentUc: payment ${command.paymentId.value} isn't there") }

        when (payment.status) {
            PaymentStatus.VERIFYING -> {
                val since = checkNotNull(payment.verifyingSince)
                if (Duration.between(since, clock.instant()) >= STUCK) {
                    logger.warn("ReconcilePaymentUc: payment ${payment.publicId.value} has been VERIFYING since $since; the gateway is asked again")
                }
                paymentConfirmation.confirm(payment, by = UserId.SYSTEM)
            }
            PaymentStatus.REDIRECTED -> reconcileRedirected(payment)
            else -> logger.info("ReconcilePaymentUc: payment ${payment.publicId.value} was settled meanwhile (${payment.status})")
        }
        return Result.success(Unit)
    }

    private suspend fun reconcileRedirected(read: Payment) {
        val authority = checkNotNull(read.authority) { "Payment ${read.publicId.value} is REDIRECTED without its session" }
        val started = TimeSource.Monotonic.markNow()
        val asked = paymentGateways.of(read.gateway)?.inquire(authority)
        val took = started.elapsedNow().toJavaDuration()
        if (asked == null) {
            logger.error("ReconcilePaymentUc: no adapter for gateway ${read.gateway} of payment ${read.publicId.value}; the configuration is wrong")
            return
        }

        val confirmNow = transactionManager.inTransaction {
            val attempts = paymentRepository.lockAttemptsOf(read.orderId)
            val payment = attempts.firstOrNull { it.publicId == read.publicId } ?: error("Payment ${read.publicId.value} was read a moment ago, yet it isn't there")
            if (payment.status != PaymentStatus.REDIRECTED) {
                logger.info("ReconcilePaymentUc: payment ${payment.publicId.value} was settled meanwhile (${payment.status}); the gateway's answer is dropped")
                return@inTransaction Result.success(null)
            }
            val order = orderRepository.get(payment.orderId) ?: error("Payment ${payment.publicId.value} is of order ${payment.orderId.value}, which has no events")
            val now = clock.instant()

            val inquiry = asked.getOrNull()
            val confirming = when (inquiry) {
                GatewayInquiry.PAID, GatewayInquiry.VERIFIED -> {
                    val begun = paymentDomainService.beginConfirming(payment, order, attempts, now)
                    if (begun != PaymentDomainService.Return.Deferred) paymentRepository.save(payment)
                    logger.info("ReconcilePaymentUc: the gateway says payment ${payment.publicId.value} was paid: $begun")
                    begun == PaymentDomainService.Return.Confirming
                }
                GatewayInquiry.FAILED, GatewayInquiry.REVERSED -> {
                    paymentDomainService.refused(payment, order, PaymentFailureReason.GATEWAY_DECLINED, UserId.SYSTEM, now)
                    orderRepository.save(order)
                    paymentRepository.save(payment)
                    logger.info("ReconcilePaymentUc: the gateway says payment ${payment.publicId.value} failed ($inquiry); the order is ${order.status}")
                    false
                }
                GatewayInquiry.IN_BANK -> {
                    if (paymentDomainService.abandon(payment, now)) {
                        paymentRepository.save(payment)
                        logger.info("ReconcilePaymentUc: payment ${payment.publicId.value} was abandoned on the gateway's page; it is EXPIRED")
                    }
                    false
                }
                null -> {
                    val failure = checkNotNull(asked.failureOrNull())
                    if (failure is GatewayFailure.Rejected) logger.error("ReconcilePaymentUc: gateway ${payment.gateway} refused our inquiry (${failure.gatewayCode}); the configuration is wrong")
                    false
                }
            }
            val outcome = inquiry?.name ?: checkNotNull(asked.failureOrNull()).logOutcome
            paymentRepository.appendLog(payment.publicId, PaymentLogEntry(PaymentLogEntry.Kind.INQUIRY, outcome, now, asked.failureOrNull()?.gatewayCode, duration = took))
            Result.success(if (confirming) payment else null)
        }.unwrapOr { it }

        confirmNow?.let { paymentConfirmation.confirm(it, by = UserId.SYSTEM) }
    }

    companion object {
        /** A confirmation not settled after this long is logged as a WARN: someone should look at the gateway. */
        val STUCK: Duration = Duration.ofMinutes(10)
    }
}
