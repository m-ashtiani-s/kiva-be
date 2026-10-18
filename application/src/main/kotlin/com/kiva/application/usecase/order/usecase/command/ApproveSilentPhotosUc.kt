package com.kiva.application.usecase.order.usecase.command

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.repository.OrderRepository
import java.time.Clock

/**
 * Approves, for the system, the photos of one order the customer didn't answer in time (the approve-silent-photos job),
 * in its own transaction: the order may then ship. The job's query only found it; the order decides here, so photos
 * answered, cancelled or changed meanwhile are left alone, and running twice approves once. Nothing in the change log:
 * it is the system's work.
 */
class ApproveSilentPhotosUc(
    private val transactionManager: TransactionManager,
    private val orderRepository: OrderRepository,
    private val clock: Clock,
) : CommandUseCase<ApproveSilentPhotosUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    data class Cmd(val orderId: OrderId)

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("ApproveSilentPhotosUc.execute orderId=${command.orderId.value}")
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return approveOnce(command.orderId)
            } catch (e: ConcurrentChangeException) {
                logger.info("ApproveSilentPhotosUc: order ${command.orderId.value} changed meanwhile; trying again")
            }
        }
        return approveOnce(command.orderId)
    }

    private suspend fun approveOnce(orderId: OrderId): Result<Unit, Nothing> = transactionManager.inTransaction {
        val order = orderRepository.get(orderId) ?: error("Order ${orderId.value} of the read model has no events")
        when (val approved = order.approvePhotos(automatic = true, UserId.SYSTEM, clock.instant())) {
            is Result.Success -> orderRepository.save(order)
            // Not due yet, or nothing waits for an answer any more: nothing to do.
            is Result.Failure -> logger.info("ApproveSilentPhotosUc: order ${order.code.value} left alone: ${approved.failure.code}")
        }
        Result.success(Unit)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
