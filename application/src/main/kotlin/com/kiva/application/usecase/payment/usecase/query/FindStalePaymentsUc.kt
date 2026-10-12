package com.kiva.application.usecase.payment.usecase.query

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import java.time.Clock
import java.time.Duration

/**
 * The reconciliation job's candidates (roadmap section 15): attempts left being confirmed for [VERIFYING_AFTER] (the
 * gateway didn't answer, or the app stopped halfway), and attempts whose shopper hasn't come back [REDIRECTED_AFTER]
 * after being sent to the gateway (the tab was closed after paying). Only candidates: ReconcilePaymentUc settles each
 * with its order's attempts locked.
 */
class FindStalePaymentsUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val clock: Clock,
) : QueryUseCase<FindStalePaymentsUc.Qry, List<PaymentPublicId>, Nothing> {

    private val logger = getLogger()

    data class Qry(val limit: Int = BATCH)

    override suspend fun execute(query: Qry): Result<List<PaymentPublicId>, Nothing> {
        logger.info("FindStalePaymentsUc.execute limit=${query.limit}")
        val now = clock.instant()
        return transactionManager.inTransaction {
            Result.success(paymentRepository.findToReconcile(now.minus(VERIFYING_AFTER), now.minus(REDIRECTED_AFTER), query.limit))
        }
    }

    companion object {
        /** A confirmation not settled this long is asked again: the callback that started it had its chance. */
        val VERIFYING_AFTER: Duration = Duration.ofMinutes(2)

        /** A shopper not back this long after being sent to the gateway is looked for at the gateway. */
        val REDIRECTED_AFTER: Duration = Duration.ofMinutes(3)

        /** At most this many a run; the rest wait for the next. */
        const val BATCH = 100
    }
}
