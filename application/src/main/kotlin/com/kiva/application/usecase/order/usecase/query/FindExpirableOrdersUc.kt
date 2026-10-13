package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Clock

/**
 * The expiry job's candidates (roadmap section 15): unpaid orders whose time to pay is over, from the read model, the
 * longest over first. Only candidates: ExpireUnpaidOrderUc decides on each with the order itself, so one paid or
 * expired since it was found here is left alone.
 */
class FindExpirableOrdersUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val clock: Clock,
) : QueryUseCase<FindExpirableOrdersUc.Qry, List<OrderId>, Nothing> {

    private val logger = getLogger()

    data class Qry(val limit: Int = BATCH)

    override suspend fun execute(query: Qry): Result<List<OrderId>, Nothing> {
        logger.info("FindExpirableOrdersUc.execute limit=${query.limit}")
        return transactionManager.inTransaction { Result.success(orderQmRepository.findExpirable(clock.instant(), query.limit)) }
    }

    companion object {
        /** At most this many a run; the rest wait a minute for the next. */
        const val BATCH = 100
    }
}
