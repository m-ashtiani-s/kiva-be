package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.AdminNoteDto
import com.kiva.application.dto.AdminOrderDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderDetails
import com.kiva.application.usecase.returnrequest.AdminReturnRequests
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.policy.OrderTransitionPolicy
import com.kiva.domain.aggregate.ordernote.repository.OrderNoteRepository
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * One order in the panel (`GET /admin/orders/{orderCode}`; ADMIN, OPERATOR), and the answer of every panel write on an
 * order: the customer's page (OrderDetails) and the customer, the shipment group, every attempt to pay, the internal
 * notes (oldest first, by their authors' names), its returns as the panel's queue shows them (AdminReturnRequests: where
 * each may go next, the suggested refund), and where
 * `POST …/status` may take it now. Any customer's order; a code that can't be one or nobody has is ORDER_NOT_FOUND.
 */
class GetAdminOrderUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderDetails: OrderDetails,
    private val orderQmRepository: OrderQmRepository,
    private val paymentRepository: PaymentRepository,
    private val reservationGroupRepository: ReservationGroupRepository,
    private val adminCustomerRepository: AdminCustomerRepository,
    private val orderNoteRepository: OrderNoteRepository,
    private val returnRequestRepository: ReturnRequestRepository,
    private val adminReturnRequests: AdminReturnRequests,
    private val userRepository: UserRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetAdminOrderUc.Qry, AdminOrderDto, GetAdminOrderUc.Failure> {

    private val logger = getLogger()

    /** @property orderCode the path's code, as it came. */
    data class Qry(val orderCode: String)

    override suspend fun execute(query: Qry): Result<AdminOrderDto, Failure> {
        val code = orderByCode.codeOf(query.orderCode)
        logger.info("GetAdminOrderUc.execute orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val order = orderByCode.findForPanel(query.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            Result.success(adminOrderOf(order))
        }
    }

    private fun adminOrderOf(order: OrderQm): AdminOrderDto {
        val settings = shopSettingsRepository.get()
        val attempts = paymentRepository.findAttemptsOf(order.orderId)
        val account = adminCustomerRepository.findById(order.userId)
        val group = order.reservationGroupId?.let(reservationGroupRepository::find)
        return AdminOrderDto(
            detail = orderDetails.of(order, settings, clock.instant(), attempts),
            returns = adminReturnRequests.of(returnRequestRepository.findOfOrder(order.orderId), settings),
            customer = AdminOrderDto.Customer(
                id = order.userId.value,
                fullName = account?.let { listOfNotNull(it.firstName?.value, it.lastName?.value).joinToString(" ").ifEmpty { null } },
                phone = account?.phone?.value ?: order.address.recipientPhone?.value,
                ordersCount = orderQmRepository.purchasesOf(listOf(order.userId))[order.userId]?.ordersCount ?: 0,
            ),
            shipmentGroup = group?.let {
                AdminOrderDto.ShipmentGroup(
                    code = it.code.value,
                    orderCodes = orderQmRepository.paidCodesIn(listOf(it.id))[it.id].orEmpty().map { code -> code.value },
                    releaseAt = it.expiresOn,
                )
            },
            payments = attempts.map { orderDetails.paymentOf(it, settings) },
            notes = orderNoteRepository.findAllOf(order.orderId).let { notes ->
                val authors = notes.map { it.authorId }.distinct().associateWith(userRepository::findById)
                notes.map { AdminNoteDto.from(it, authors[it.authorId]) }
            },
            allowedTransitions = OrderTransitionPolicy.allowedFrom(order.status),
        )
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
