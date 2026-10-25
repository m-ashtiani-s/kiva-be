package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Clock

/**
 * The approve-silent-photos job's candidates (every 10 minutes): orders whose photos were sent and got no answer by their
 * deadline, the longest past first, at most [BATCH]. Only candidates: ApproveSilentPhotosUc looks at each one again.
 */
class FindSilentPhotoOrdersUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val clock: Clock,
) : QueryUseCase<FindSilentPhotoOrdersUc.Qry, List<OrderId>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<OrderId>, Nothing> {
        val now = clock.instant()
        return transactionManager.inTransaction { Result.success(orderQmRepository.findSilentPhotos(now, BATCH)) }
            .also { logger.info("FindSilentPhotoOrdersUc: ${it.getOrNull()?.size} past their deadline at $now") }
    }

    companion object {
        const val BATCH = 100
    }
}
