package com.kiva.domain.aggregate.order.repository

import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderId

/**
 * Where orders are kept: as their events, not as a row. Only these two, as for every event-sourced aggregate; the
 * customer's lists and pages read the order read model instead.
 */
interface OrderRepository {
    /** The order rebuilt from its events, or null if it has none. */
    fun get(id: OrderId): Order?

    /**
     * Appends the order's new events ([Order.getRecordedEvents]) to its stream, and keeps the read model in step.
     *
     * Throws ConcurrentChangeException when someone else saved a change to it after it was read (a callback and the
     * expiry job at the same moment): the caller's transaction then rolls back, and its use case decides again.
     */
    fun save(order: Order)
}
