package com.kiva.application.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import java.time.Instant

/**
 * The order read model: what use cases read about orders, instead of replaying their events. Only OrderQmProjector
 * writes it, in the same transaction as the events, so inside a use case's transaction it shows every saved change.
 *
 * Its lists (the customer's tabs, the jobs' candidates) come with the steps that need them.
 */
interface OrderQmRepository {

    /**
     * The customer's order with this code. Null when no order has it or it is someone else's: the owner is part of the
     * question, so another customer's order is the same 404 as one that doesn't exist.
     */
    fun findOfCustomer(userId: UserId, code: OrderCode): OrderQm?

    /**
     * When each of the customer's unpaid orders that still hold stock stops holding it: unpaid, and their time to pay
     * not over at [now]. What the limit of unpaid orders counts (PaymentPolicy.refusalToPlace).
     */
    fun unpaidHoldingUntil(userId: UserId, now: Instant): List<Instant>
}
