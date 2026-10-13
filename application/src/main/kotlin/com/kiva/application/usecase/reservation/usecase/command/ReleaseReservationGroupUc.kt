package com.kiva.application.usecase.reservation.usecase.command

import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.reservation.ReservationDomainService
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import java.time.Clock

/**
 * Lets one reservation go when its time is over (the release job): its held orders go to be prepared
 * (OrderReleasedFromReservation, RESERVED to PROCESSING), and it is RELEASED. In its own transaction, under the
 * customer's reservation lock (section 11, lock 4), so a payment joining it at the same moment is either in before
 * (and released with it) or finds it let go (and is prepared at once). Running twice, or on two instances, changes
 * nothing twice; a group whose time isn't over is left alone.
 */
class ReleaseReservationGroupUc(
    private val transactionManager: TransactionManager,
    private val reservationGroupRepository: ReservationGroupRepository,
    private val orderRepository: OrderRepository,
    private val orderQmRepository: OrderQmRepository,
    private val reservationDomainService: ReservationDomainService,
    private val clock: Clock,
) : CommandUseCase<ReleaseReservationGroupUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    data class Cmd(val groupId: ReservationGroupId)

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("ReleaseReservationGroupUc.execute groupId=${command.groupId.value}")
        return transactionManager.inTransaction {
            val found = reservationGroupRepository.find(command.groupId) ?: return@inTransaction Result.success(Unit)
            reservationGroupRepository.lockFor(found.userId)
            // Read again under the lock: what a payment or another run did meanwhile is seen.
            val group = reservationGroupRepository.find(command.groupId) ?: error("Group ${found.code.value} was found a moment ago, yet it isn't there")
            val held = orderQmRepository.findHeldIn(group.id).map { orderRepository.get(it) ?: error("Order ${it.value} of group ${group.code.value} has no events") }
            if (reservationDomainService.release(group, held, clock.instant())) {
                held.forEach(orderRepository::save)
                reservationGroupRepository.save(group)
                logger.info("ReleaseReservationGroupUc: group ${group.code.value} let go of ${held.map { it.code.value }}")
            }
            Result.success(Unit)
        }
    }
}
