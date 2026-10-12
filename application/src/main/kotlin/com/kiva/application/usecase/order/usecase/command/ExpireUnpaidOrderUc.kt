package com.kiva.application.usecase.order.usecase.command

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.PaymentDomainService
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import java.time.Clock

/**
 * Expires one unpaid order whose time to pay is over (the expiry job, roadmap section 15), in its own transaction: the
 * order expires, its stock goes back to the shop, and its attempts still open expire with it. The job's query only
 * found it; the order decides here, with every attempt of it locked, so:
 *
 * - one paid or expired meanwhile is left alone (ORDER_NOT_AWAITING_PAYMENT): running twice, or two app instances
 *   running at once, never expire twice nor give the stock back twice;
 * - one with an attempt being confirmed, or a shopper perhaps still on the bank's page, waits for the next run
 *   (PAYMENT_IN_PROGRESS), so a payment made at the last minute still finds its bag (section 3, row 4).
 *
 * Locks in section 11's order: the order's attempts, the products and colors (the stock, sorted), the order's stream.
 * When the stock changed at the same moment (an order placed for the same bag), the whole transaction runs again.
 */
class ExpireUnpaidOrderUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val paymentDomainService: PaymentDomainService,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val clock: Clock,
) : CommandUseCase<ExpireUnpaidOrderUc.Cmd, Unit, ExpireUnpaidOrderUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val orderId: OrderId)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("ExpireUnpaidOrderUc.execute orderId=${command.orderId.value}")
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return expireOnce(command.orderId)
            } catch (e: ConcurrentChangeException) {
                logger.info("ExpireUnpaidOrderUc: changed meanwhile (${e.message}), attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        return expireOnce(command.orderId)
    }

    private suspend fun expireOnce(orderId: OrderId): Result<Unit, Failure> = transactionManager.inTransaction {
        val attempts = paymentRepository.lockAttemptsOf(orderId)
        val order = orderRepository.get(orderId) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
        val now = clock.instant()

        val expired = when (val result = paymentDomainService.expireUnpaid(order, attempts, UserId.SYSTEM, now)) {
            is Result.Success -> result.value
            is Result.Failure -> {
                logger.info("ExpireUnpaidOrderUc: order ${order.code.value} is not expired now: ${result.failure.code} ${result.failure.meta}")
                return@inTransaction Result.failure(Failure.NotNow(result.failure))
            }
        }
        inventoryDomainService.releaseForOrder(order.code, order.lines, UserId.SYSTEM, now).forEach(inventoryRepository::save)
        orderRepository.save(order)
        expired.forEach(paymentRepository::save)
        logger.info("ExpireUnpaidOrderUc: order ${order.code.value} expired, its stock given back, attempts expired: ${expired.map { it.publicId.value }}")
        Result.success(Unit)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not expired now: paid or expired already, not due, or an attempt may still pay it. Nothing changed. */
        data class NotNow(val refusal: PaymentDomainService.ExpireRefusal) : Failure(refusal.code, refusal.kind, refusal.meta)
    }
}
