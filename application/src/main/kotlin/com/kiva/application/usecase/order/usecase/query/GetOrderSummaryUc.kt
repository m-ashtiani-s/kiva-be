package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.OrderSummaryDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * One order's card (the contract's OrderSummary): the `order` of placing an order's answer, from its read model. The
 * customer's own only: someone else's order is ORDER_NOT_FOUND, exactly like one that doesn't exist.
 */
class GetOrderSummaryUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetOrderSummaryUc.Qry, OrderSummaryDto, GetOrderSummaryUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token, never from the request. */
    data class Qry(val userId: UserId, val orderCode: OrderCode)

    override suspend fun execute(query: Qry): Result<OrderSummaryDto, Failure> {
        logger.info("GetOrderSummaryUc.execute userId=${query.userId.value} orderCode=${query.orderCode.value}")
        return transactionManager.inTransaction {
            val order = orderQmRepository.findOfCustomer(query.userId, query.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            Result.success(OrderSummaryDto.of(order, shopSettingsRepository.get(), clock.instant()))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
