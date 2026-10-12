package com.kiva.application.usecase.checkout.usecase.command

import com.kiva.application.port.IdempotencyScope
import com.kiva.application.port.IdempotentRequest
import com.kiva.application.port.PaymentGateways
import com.kiva.application.port.RequestHash
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.idempotency.IdempotencyGuard
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.PaymentDomainService
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * Another attempt to pay an unpaid order (roadmap section 10.8): `POST /payments/{paymentId}/retry` names it by an
 * earlier attempt, `POST /me/orders/{orderCode}/payments` by its code, because the order's page has no attempt id. Both
 * take an `Idempotency-Key`, like placing an order.
 *
 * 1. The shape, before any transaction (400): the key, and the gateway when one is asked for.
 * 2. One transaction: the key (a repeat answers the attempt it made; another request with it is
 *    IDEMPOTENCY_KEY_REUSED); the customer's own attempt or order, or 404 like one that doesn't exist; every attempt of
 *    the order locked together (section 11, lock 6), so a callback, the jobs and another retry of the order wait; then
 *    PaymentDomainService's rules, in the contract's order: paid already, time to pay over, gateway not available, too
 *    many attempts. The gateway is the one asked for, or else the one of the attempt named (by order: of its latest
 *    attempt). The new attempt is for the order's payable, and its time to pay is the order's own, never moved.
 *
 * Returns the new attempt's id. Opening its session is StartPaymentUc's, which the route calls after this commits, as
 * after placing an order: a gateway is never called inside a transaction, and a repeat never opens another session.
 */
class StartPaymentAttemptUc(
    private val transactionManager: TransactionManager,
    private val idempotencyGuard: IdempotencyGuard,
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val paymentDomainService: PaymentDomainService,
    private val paymentGateways: PaymentGateways,
    private val clock: Clock,
) : CommandUseCase<StartPaymentAttemptUc.Cmd, PaymentPublicId, StartPaymentAttemptUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property of the attempt or the order, as the path names it.
     * @property idempotencyKey the `Idempotency-Key` header as it arrived; [requestHash] the request's fingerprint.
     * @property gateway the body's `gateway`, as it arrived; null keeps the gateway of before.
     */
    data class Cmd(val userId: UserId, val of: Target, val idempotencyKey: String?, val requestHash: RequestHash, val gateway: String?)

    /** Which order to pay again, as the path names it, raw. */
    sealed interface Target {
        /** `POST /payments/{paymentId}/retry`: by an earlier attempt of it. */
        data class Attempt(val paymentId: String) : Target

        /** `POST /me/orders/{orderCode}/payments`: by its code. */
        data class Order(val orderCode: String) : Target
    }

    override suspend fun execute(command: Cmd): Result<PaymentPublicId, Failure> {
        val scope = when (command.of) {
            is Target.Attempt -> IdempotencyScope.RETRY_PAYMENT
            is Target.Order -> IdempotencyScope.PAY_ORDER
        }
        // The body's gateway may be anything: only a gateway's name reaches the log.
        val logged = command.gateway?.let { PaymentGatewayCode.makeNew(it).getOrNull()?.name ?: "(not a gateway)" } ?: "as before"
        logger.info("StartPaymentAttemptUc.execute userId=${command.userId.value} scope=$scope gateway=$logged")

        val problems = mutableListOf<ResultFailure>()
        val request = IdempotentRequest.makeNew(command.userId, scope, command.idempotencyKey, command.requestHash).failureOrProblem(problems)
        val gateway = command.gateway?.let { PaymentGatewayCode.makeNew(it).forField("gateway").failureOrProblem(problems) }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        checkNotNull(request)

        return transactionManager.inTransaction {
            idempotencyGuard.once(request, outcomeOf = { id: PaymentPublicId -> mapOf(PAYMENT_ID to id.value) }) { start(command, gateway) }
                .bind { run ->
                    when (run) {
                        is IdempotencyGuard.Run.Done -> Result.success(run.value)
                        is IdempotencyGuard.Run.Replayed ->
                            Result.success(PaymentPublicId.constructFromPersisted(run.outcome[PAYMENT_ID] ?: error("A retry's key kept no payment: ${run.outcome.keys}")))
                        IdempotencyGuard.Run.KeyReused -> Result.failure(Failure.KeyReused)
                    }
                }
        }
    }

    private fun start(command: Cmd, asked: PaymentGatewayCode?): Result<PaymentPublicId, Failure> {
        val userId = command.userId
        val orderId = orderIdOf(command.of, userId).unwrapOr { return Result.failure(it) }

        val attempts = paymentRepository.lockAttemptsOf(orderId)
        val order = orderRepository.get(orderId) ?: error("Order ${orderId.value} has attempts to pay but no events")
        val before = when (val of = command.of) {
            is Target.Attempt -> attempts.first { it.publicId.value == of.paymentId }
            is Target.Order -> attempts.lastOrNull() ?: error("Unpaid order ${order.code.value} has never had an attempt to pay")
        }
        val gateway = asked ?: before.gateway
        val settings = shopSettingsRepository.get()

        val attempt = paymentDomainService.startAttempt(order, attempts, gateway, paymentGateways.isAvailable(gateway, settings), userId, clock.instant())
            .unwrapOr { return Result.failure(Failure.Refused(it)) }
        paymentRepository.add(attempt)
        logger.info("StartPaymentAttemptUc: attempt ${attempt.publicId.value} of order ${order.code.value} (${attempts.size + 1} in all) through $gateway")
        return Result.success(attempt.publicId)
    }

    /** The customer's own order, by an attempt of it or by its code: anything else is the same 404 as one that isn't there. */
    private fun orderIdOf(of: Target, userId: UserId): Result<OrderId, Failure> = when (of) {
        is Target.Attempt ->
            PaymentPublicId.of(of.paymentId)?.let(paymentRepository::find)?.takeIf { it.userId == userId }?.let { Result.success(it.orderId) }
                ?: Result.failure(Failure.PaymentNotFound)
        is Target.Order ->
            OrderCode.makeNew(of.orderCode).getOrNull()?.let { orderQmRepository.findOfCustomer(userId, it) }?.let { Result.success(it.orderId) }
                ?: Result.failure(Failure.OrderNotFound)
    }

    private fun <T> Result<T, ResultFailure>.failureOrProblem(problems: MutableList<ResultFailure>): T? = when (this) {
        is Result.Success -> value
        is Result.Failure -> null.also { problems += failure }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** The key was used before for another request (409). */
        data object KeyReused : Failure("IDEMPOTENCY_KEY_REUSED", FailureKind.CONFLICT)

        data object PaymentNotFound : Failure("PAYMENT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** One of the rules of a new attempt: paid already, time over, gateway not available, too many attempts. */
        data class Refused(val failure: PaymentDomainService.StartAttemptFailure) : Failure(failure.code, failure.kind, meta = failure.meta)
    }

    private companion object {
        const val PAYMENT_ID = "paymentId"
    }
}
