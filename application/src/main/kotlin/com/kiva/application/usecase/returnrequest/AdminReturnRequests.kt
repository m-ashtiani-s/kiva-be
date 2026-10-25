package com.kiva.application.usecase.returnrequest

import com.kiva.application.dto.AdminReturnRequestDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.refund.RefundDomainService
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnRefundPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.settings.entity.ShopSettings

/**
 * Returns as the panel sees them (the contract's AdminReturnRequest): what the customer sees (ReturnRequests), and the
 * customer's account now, the receipt, where each may go next with its refunds counted, and for a RECEIVED one the money
 * it suggests giving back (ReturnRefundPolicy, at most what its order's money still allows). The queue, and the answer of
 * every step staff take. A whole page in a few reads, never one per return: the orders, the accounts, the orders'
 * refunds and, for the RECEIVED ones only, their payments. Runs inside the caller's transaction.
 */
class AdminReturnRequests(
    private val returnRequests: ReturnRequests,
    private val adminCustomerRepository: AdminCustomerRepository,
    private val refundRepository: RefundRepository,
    private val paymentRepository: PaymentRepository,
    private val refundDomainService: RefundDomainService,
) {

    /** [returns], in their order. */
    fun of(returns: List<ReturnRequest>, settings: ShopSettings): List<AdminReturnRequestDto> {
        if (returns.isEmpty()) return emptyList()
        val orders = returnRequests.ordersOf(returns)
        val seen = returnRequests.of(returns, orders, settings)
        val accounts = adminCustomerRepository.findAll(returns.map { it.userId }.distinct())
        val refunds = refundRepository.findAllOfOrders(orders.keys).groupBy { it.orderId }
        val received = returns.filter { it.status == ReturnStatus.RECEIVED }.map { it.orderId }.distinct()
        val payments = paymentRepository.findAttemptsOfOrders(received).groupBy { it.orderId }

        return returns.zip(seen) { ret, dto ->
            val order = orders.getValue(ret.orderId)
            val ofOrder = refunds[ret.orderId].orEmpty()
            val account = accounts[ret.userId]
            val receipt = ret.receipt
            AdminReturnRequestDto(
                ret = dto,
                customer = AdminReturnRequestDto.Customer(
                    id = ret.userId.value,
                    fullName = account?.let { listOfNotNull(it.firstName?.value, it.lastName?.value).joinToString(" ").ifEmpty { null } },
                    phone = account?.phone?.value ?: order.address.recipientPhone?.value,
                ),
                receipt = receipt?.let { AdminReturnRequestDto.Receipt(it.lines.map { line -> AdminReturnRequestDto.Line(line.lineId, line.received, line.restock) }, it.note?.value) },
                allowedTransitions = ret.allowedTransitions(hasOpenRefund = ofOrder.any { it.isOpenFor(ret) }),
                suggestedRefund = if (ret.status == ReturnStatus.RECEIVED && receipt != null) {
                    val refundable = refundDomainService.refundable(refundDomainService.paidBy(payments[ret.orderId].orEmpty()), ofOrder)
                    ReturnRefundPolicy.suggestedRefund(order.lines, order.totals, receipt, refundable)
                } else {
                    null
                },
            )
        }
    }

    /** One return: what every step staff take answers with. */
    fun of(ret: ReturnRequest, settings: ShopSettings): AdminReturnRequestDto = of(listOf(ret), settings).single()

    private fun Refund.isOpenFor(ret: ReturnRequest): Boolean = returnId == ret.id.value && (status == RefundStatus.PENDING || status == RefundStatus.PROCESSING)
}
