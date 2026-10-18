package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.AdminOrderListDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.AdminOrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.order.AdminOrderFilterQuery
import com.kiva.application.usecase.order.AdminOrderFilters
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupCode
import com.kiva.domain.aggregate.user.entity.User

/**
 * The panel's order queue (`GET /admin/orders`; ADMIN, OPERATOR). Without a status it is what was paid (the unpaid and
 * expired orders come only when asked for); `statusCounts` counts every status with the same filters but the status.
 * Three reads: the page (with its groups), the counts, and the accounts of the page's orders.
 */
class FindAdminOrdersUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val adminCustomerRepository: AdminCustomerRepository,
) : QueryUseCase<FindAdminOrdersUc.Qry, AdminOrderListDto, FindAdminOrdersUc.Failure> {

    private val logger = getLogger()

    /** @property filters the query parameters as they came; [pageable] carries the sort (AdminOrderSorts). */
    data class Qry(val pageable: Pageable, val filters: AdminOrderFilterQuery)

    override suspend fun execute(query: Qry): Result<AdminOrderListDto, Failure> {
        logger.info("FindAdminOrdersUc.execute statuses=${query.filters.statuses} page=${query.pageable.page} sort=${query.pageable.sort}")
        val filter = AdminOrderFilters.parse(query.filters).unwrapOr { return Result.failure(Failure.InvalidInput(it.problems)) }

        return transactionManager.inTransaction {
            val page = orderQmRepository.findForAdmin(filter, query.pageable)
            val counts = orderQmRepository.statusCounts(filter.withoutStatuses())
            val accounts = adminCustomerRepository.findAll(page.items.map { it.order.userId }.distinct())
            Result.success(
                AdminOrderListDto(
                    page = Page(page.items.map { itemOf(it, accounts[it.order.userId]) }, page.page, page.size, page.totalItems),
                    statusCounts = OrderStatus.entries.associateWith { counts[it] ?: 0L },
                ),
            )
        }
    }

    private fun itemOf(row: AdminOrderQm, account: User?): AdminOrderListDto.Item {
        val order = row.order
        val accountName = account?.let { listOfNotNull(it.firstName?.value, it.lastName?.value).joinToString(" ").ifEmpty { null } }
        return AdminOrderListDto.Item(
            code = order.code.value,
            placedOn = order.placedOn,
            status = order.status,
            customerName = accountName ?: order.address.recipientName.value,
            customerPhone = account?.phone?.value ?: order.address.recipientPhone.value,
            city = order.address.cityName,
            itemsCount = order.totalsView.itemsCount,
            payable = order.totalsView.payable,
            shippingMethod = order.shippingMethod,
            messenger = order.messenger.channel,
            reserved = order.reservationGroupId != null,
            reservationExpiresAt = row.groupHoldsUntil,
            shipmentGroupCode = order.reservationGroupId?.let { ReservationGroupCode.of(it).value },
            mediaCount = order.photosSent.size.takeIf { it > 0 } ?: order.photoDraft.size,
            trackingCode = order.shipment?.trackingCode?.value,
        )
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** One or more parameters are wrong; each cause names its parameter (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
