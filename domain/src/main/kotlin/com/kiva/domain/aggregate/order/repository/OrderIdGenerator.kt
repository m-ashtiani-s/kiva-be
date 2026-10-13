package com.kiva.domain.aggregate.order.repository

import com.kiva.domain.aggregate.order.model.valueobject.OrderId

/**
 * Hands out new order ids, each only once, even to two orders placed at the same moment. An order has no row before
 * its first event, so its id (and the code made from it) must exist before it is placed. The real one reads the
 * `orderid_seq` database sequence (step 9).
 */
interface OrderIdGenerator {
    fun nextOrderId(): OrderId
}
