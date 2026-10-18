package com.kiva.application.usecase.order

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.repository.OrderRepository

/**
 * An order named by the code in a path (Rule 15: every order route of the panel and of «سفارش‌های من» looks it up the
 * same way). A code that can't be one, an order nobody has, and, for a customer, someone else's order are all «not
 * found» (null): the same 404, so nothing tells them apart. The code goes through the read model to the order's id;
 * a change then loads the aggregate itself.
 *
 * Runs inside the caller's transaction.
 */
class OrderByCode(
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
) {

    /** The code of [raw], when it can be one. */
    fun codeOf(raw: String?): OrderCode? = raw?.let { OrderCode.makeNew(it).getOrNull() }

    /** The panel's: any customer's order, as its read model has it. */
    fun findForPanel(raw: String?): OrderQm? = codeOf(raw)?.let(orderQmRepository::findByCode)

    /** The customer's own order, as its read model has it. */
    fun findOfCustomer(userId: UserId, raw: String?): OrderQm? = codeOf(raw)?.let { orderQmRepository.findOfCustomer(userId, it) }

    /** The panel's: any customer's order, rebuilt from its events to be changed. */
    fun loadForPanel(raw: String?): Order? = findForPanel(raw)?.let { orderRepository.get(it.orderId) }

    /** The customer's own order, rebuilt from its events to be changed. */
    fun loadOfCustomer(userId: UserId, raw: String?): Order? = findOfCustomer(userId, raw)?.let { orderRepository.get(it.orderId) }
}
