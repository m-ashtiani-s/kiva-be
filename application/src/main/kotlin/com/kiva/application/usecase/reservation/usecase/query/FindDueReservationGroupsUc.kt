package com.kiva.application.usecase.reservation.usecase.query

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import java.time.Clock

/**
 * The release job's candidates (roadmap section 13): reservations still HOLDING whose time is over. Only candidates:
 * ReleaseReservationGroupUc decides on each under the customer's lock.
 */
class FindDueReservationGroupsUc(
    private val transactionManager: TransactionManager,
    private val reservationGroupRepository: ReservationGroupRepository,
    private val clock: Clock,
) : QueryUseCase<FindDueReservationGroupsUc.Qry, List<ReservationGroupId>, Nothing> {

    private val logger = getLogger()

    data class Qry(val limit: Int = BATCH)

    override suspend fun execute(query: Qry): Result<List<ReservationGroupId>, Nothing> {
        logger.info("FindDueReservationGroupsUc.execute limit=${query.limit}")
        return transactionManager.inTransaction { Result.success(reservationGroupRepository.findDue(clock.instant(), query.limit)) }
    }

    companion object {
        const val BATCH = 100
    }
}
