package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderDetails
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * One order's page (`GET /me/orders/{orderCode}`): its card, its lines, address, totals and notes as they were when it
 * was placed, its latest attempt to pay, and its timeline (OrderDetails). The customer's own only: someone else's order,
 * one that doesn't exist and a code that can't be one are all ORDER_NOT_FOUND (OrderByCode).
 */
class GetMyOrderUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderDetails: OrderDetails,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetMyOrderUc.Qry, OrderDetailDto, GetMyOrderUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property orderCode the path's code, as it came.
     */
    data class Qry(val userId: UserId, val orderCode: String)

    override suspend fun execute(query: Qry): Result<OrderDetailDto, Failure> {
        val code = orderByCode.codeOf(query.orderCode)
        // A code from the path may be anything: only one that can be a code is written to the log.
        logger.info("GetMyOrderUc.execute userId=${query.userId.value} orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val order = orderByCode.findOfCustomer(query.userId, query.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            Result.success(orderDetails.of(order, shopSettingsRepository.get(), clock.instant()))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
