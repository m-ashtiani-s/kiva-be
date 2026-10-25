package com.kiva.application.usecase.returnrequest

import com.kiva.application.repository.OrderQmRepository
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository

/**
 * How every step staff take on a return starts (roadmap section 12.6), in the order of locks (section 4.11): the return
 * read without a lock, only for its order; the order's row (lock 9); the return's row (lock 10), read again under it;
 * then the order itself. The order comes first because a return changes its order too, and everything else that
 * changes a delivered order (a refund, a cancellation) locks the order's row first. Reading the return without a lock
 * first is safe: its order never changes. Runs inside the caller's transaction.
 */
class ReturnByIdForPanel(
    private val returnRequestRepository: ReturnRequestRepository,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
) {

    /** A return under its lock, with its order. */
    data class Locked(val ret: ReturnRequest, val order: Order)

    /** The return [id] and its order, both locked; null when there is no such return. */
    fun lock(id: ReturnId): Locked? {
        val read = returnRequestRepository.find(id) ?: return null
        orderQmRepository.lockOrder(read.orderId) ?: error("Return ${id.value} of order ${read.orderId.value}, which the read model doesn't have")
        val ret = returnRequestRepository.lock(id) ?: error("Return ${id.value} went while its order was locked")
        val order = orderRepository.get(ret.orderId) ?: error("Order ${ret.orderId.value} of return ${id.value} has no events")
        return Locked(ret, order)
    }
}
