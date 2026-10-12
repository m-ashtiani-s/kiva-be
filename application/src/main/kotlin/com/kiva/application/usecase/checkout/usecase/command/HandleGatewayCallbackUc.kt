package com.kiva.application.usecase.checkout.usecase.command

import com.kiva.application.port.PaymentGateways
import com.kiva.application.usecase.payment.PaymentConfirmation
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.PaymentDomainService
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import java.time.Clock

/**
 * The shopper back from the gateway (`GET` and `POST /payment-callbacks/{gateway}`, roadmap section 10.6). With
 * StartPaymentUc, the only use case with two transactions, and the gateway called **between** them, never inside one.
 *
 * 1. The callback's parameters are read by the gateway's adapter (Zarinpal: `Authority`, `Status`). Not this gateway's,
 *    or a gateway the app has no adapter for: nothing happens.
 * 2. The attempt is found by the session's authority the gateway gave it, never by an id in the callback's address: a
 *    forged callback can only name a session the gateway really opened for us, and it is then confirmed server to
 *    server with the amount we stored.
 * 3. [tx] Every attempt of its order is locked and PaymentDomainService decides: an attempt that ended already changes
 *    nothing (a refresh, or GET and POST together); NOK cancels it and fails the order's try; OK while the order no
 *    longer waits fails it as ORDER_NOT_PAYABLE, never confirmed, so the gateway gives the money back by itself; OK while
 *    another attempt of the order is being confirmed waits for the reconciliation job; OK otherwise starts confirming.
 *    The callback goes into the payment log.
 * 4. Only an attempt that started confirming here goes on: PaymentConfirmation asks the gateway outside any transaction
 *    and settles it (paid, refused, or still VERIFYING when the gateway didn't answer), and «پرداخت موفق» is sent after.
 *
 * It never fails: whatever happened, the route sends the shopper to the result page, with the attempt's id when one was
 * found ([PaymentPublicId]), without it otherwise (contract).
 */
class HandleGatewayCallbackUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val paymentDomainService: PaymentDomainService,
    private val paymentGateways: PaymentGateways,
    private val paymentConfirmation: PaymentConfirmation,
    private val clock: Clock,
) : CommandUseCase<HandleGatewayCallbackUc.Cmd, PaymentPublicId?, Nothing> {

    private val logger = getLogger()

    /**
     * @property gateway the path's gateway, as the callback's address names it (`zarinpal`).
     * @property params the callback's parameters, from its query or its form, as they came.
     */
    data class Cmd(val gateway: String, val params: Map<String, String>) {
        // Anyone can call a callback's address with anything: only letters of the path's gateway reach the log.
        override fun toString(): String = "Cmd(gateway=${gateway.filter { it in 'a'..'z' || it in 'A'..'Z' }.take(10)}, params=${params.size})"
    }

    /** What the first transaction found and did. */
    private class Returned(val payment: Payment, val outcome: PaymentDomainService.Return)

    override suspend fun execute(command: Cmd): Result<PaymentPublicId?, Nothing> {
        logger.info("HandleGatewayCallbackUc.execute $command")

        val code = PaymentGatewayCode.entries.firstOrNull { it.name.equals(command.gateway, ignoreCase = true) }
        val adapter = code?.let(paymentGateways::of)
        val callback = adapter?.readCallback(command.params)
        if (code == null || callback == null) {
            logger.info("HandleGatewayCallbackUc: not a callback of a gateway this app takes payments with ($code)")
            return Result.success(null)
        }

        val found = transactionManager.inTransaction { Result.success(paymentRepository.findByAuthority(code, callback.authority)) }.unwrapOr { it }
        if (found == null) {
            logger.info("HandleGatewayCallbackUc: no payment of gateway $code has this session")
            return Result.success(null)
        }

        val returned = transactionManager.inTransaction { Result.success(back(found, callback.paid)) }.unwrapOr { it }
        if (returned.outcome == PaymentDomainService.Return.Confirming) {
            paymentConfirmation.confirm(returned.payment, by = returned.payment.userId)
        }
        return Result.success(found.publicId)
    }

    private fun back(found: Payment, paid: Boolean): Returned {
        val attempts = paymentRepository.lockAttemptsOf(found.orderId)
        val payment = attempts.firstOrNull { it.publicId == found.publicId } ?: error("Payment ${found.publicId.value} was found a moment ago, yet it isn't there")
        val order = orderRepository.get(payment.orderId) ?: error("Payment ${payment.publicId.value} is of order ${payment.orderId.value}, which has no events")
        val now = clock.instant()

        // The shopper's own payment: their browser brought the callback.
        val outcome = paymentDomainService.shopperReturned(payment, paid, order, attempts, by = payment.userId, now = now)
        when (outcome) {
            PaymentDomainService.Return.Cancelled, PaymentDomainService.Return.NotPayable, PaymentDomainService.Return.Confirming -> {
                orderRepository.save(order)
                paymentRepository.save(payment)
            }
            PaymentDomainService.Return.AlreadySettled, PaymentDomainService.Return.BeingConfirmed, PaymentDomainService.Return.Deferred -> Unit
        }
        paymentRepository.appendLog(payment.publicId, PaymentLogEntry(PaymentLogEntry.Kind.CALLBACK, logOutcomeOf(outcome, paid), now))
        logger.info("HandleGatewayCallbackUc: payment ${payment.publicId.value} came back ${if (paid) "OK" else "NOK"}: $outcome, now ${payment.status}")
        return Returned(payment, outcome)
    }

    private fun logOutcomeOf(outcome: PaymentDomainService.Return, paid: Boolean): String = when (outcome) {
        PaymentDomainService.Return.AlreadySettled -> if (paid) "OK_ALREADY_SETTLED" else "NOK_ALREADY_SETTLED"
        PaymentDomainService.Return.BeingConfirmed -> if (paid) "OK_BEING_CONFIRMED" else "NOK_BEING_CONFIRMED"
        PaymentDomainService.Return.Cancelled -> "NOK"
        PaymentDomainService.Return.NotPayable -> "OK_NOT_PAYABLE"
        PaymentDomainService.Return.Deferred -> "OK_DEFERRED"
        PaymentDomainService.Return.Confirming -> "OK"
    }
}
