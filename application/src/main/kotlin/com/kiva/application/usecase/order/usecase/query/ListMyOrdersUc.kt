package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.OrderListDto
import com.kiva.application.dto.OrderSummaryDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.order.OrderDetails
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderTab
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * «سفارش‌های من» (`GET /me/orders`): the customer's orders, newest first, one page of one tab (`filter`: all, current,
 * delivered, cancelled; OrderTab says which status is where), and the counts of every tab. From the read model only:
 * a list never reads the event store.
 */
class ListMyOrdersUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val reservations: Reservations,
    private val orderDetails: OrderDetails,
    private val clock: Clock,
) : QueryUseCase<ListMyOrdersUc.Qry, OrderListDto, ListMyOrdersUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property filter the tab as the query names it; null is `all`.
     */
    data class Qry(val userId: UserId, val filter: String?, val pageable: Pageable)

    override suspend fun execute(query: Qry): Result<OrderListDto, Failure> {
        val tab = when (val filter = query.filter?.trim()?.ifEmpty { null } ?: ALL) {
            ALL -> null
            else -> OrderTab.entries.firstOrNull { it.name.lowercase() == filter } ?: return Result.failure(Failure.InvalidInput(listOf(FilterInvalid)))
        }
        logger.info("ListMyOrdersUc.execute userId=${query.userId.value} tab=${tab ?: ALL} page=${query.pageable.page}")

        return transactionManager.inTransaction {
            val settings = shopSettingsRepository.get()
            val now = clock.instant()
            val orders = orderQmRepository.listOfCustomer(query.userId, tab?.statuses, query.pageable)
            val held = reservations.infosOf(orders.items, now)
            val media = orderDetails.mediaOf(orders.items)
            val page = orders.map { OrderSummaryDto.of(it, settings, now, held[it.orderId], media) }
            val byStatus = orderQmRepository.countByStatusOfCustomer(query.userId)
            fun countOf(tab: OrderTab) = byStatus.filterKeys { OrderTab.of(it) == tab }.values.sum()
            val counts = OrderListDto.Counts(
                all = byStatus.values.sum(),
                current = countOf(OrderTab.CURRENT),
                delivered = countOf(OrderTab.DELIVERED),
                cancelled = countOf(OrderTab.CANCELLED),
            )
            Result.success(OrderListDto(page, counts))
        }
    }

    /** `filter` is none of all, current, delivered, cancelled. */
    data object FilterInvalid : ResultFailure("ORDER_FILTER_INVALID", FailureKind.INVALID, field = "filter")

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        const val ALL = "all"
    }
}
