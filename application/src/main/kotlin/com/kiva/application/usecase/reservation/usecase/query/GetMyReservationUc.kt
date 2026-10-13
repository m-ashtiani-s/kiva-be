package com.kiva.application.usecase.reservation.usecase.query

import com.kiva.application.dto.ReservationInfoDto
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import java.time.Clock

/**
 * «رزرو ۴ روزه» of the customer (`GET /me/reservation`): the countdown and the orders in it, while one holds; null (204)
 * otherwise.
 */
class GetMyReservationUc(
    private val transactionManager: TransactionManager,
    private val reservations: Reservations,
    private val clock: Clock,
) : QueryUseCase<GetMyReservationUc.Qry, ReservationInfoDto?, Nothing> {

    private val logger = getLogger()

    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<ReservationInfoDto?, Nothing> {
        logger.info("GetMyReservationUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction { Result.success(reservations.activeInfoOf(query.userId, clock.instant())) }
    }
}
