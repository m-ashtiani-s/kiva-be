package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Clock

/**
 * The publish job's candidates (`publish-scheduled-products`, every minute): drafts whose schedule is due, earliest
 * first, at most [BATCH]. Only candidates: PublishScheduledProductUc looks at each one again before publishing it.
 */
class FindDueScheduledProductsUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val clock: Clock,
) : QueryUseCase<FindDueScheduledProductsUc.Qry, List<ProductId>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<ProductId>, Nothing> {
        val now = clock.instant()
        return transactionManager.inTransaction { Result.success(productQmRepository.findDueScheduled(now, BATCH)) }
            .also { logger.info("FindDueScheduledProductsUc: ${it.getOrNull()?.size} due at $now") }
    }

    companion object {
        const val BATCH = 100
    }
}
