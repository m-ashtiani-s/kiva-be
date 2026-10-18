package com.kiva.application.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import java.time.Instant

/**
 * The order read model: what use cases read about orders, instead of replaying their events. Only OrderQmProjector
 * writes it, in the same transaction as the events, so inside a use case's transaction it shows every saved change.
 *
 * Its lists: the customer's tabs, the panel's numbers of a customer, and the jobs' candidates.
 */
interface OrderQmRepository {

    /**
     * The customer's order with this code. Null when no order has it or it is someone else's: the owner is part of the
     * question, so another customer's order is the same 404 as one that doesn't exist.
     */
    fun findOfCustomer(userId: UserId, code: OrderCode): OrderQm?

    /** The order with this code, whoever's it is (the panel); null when none has it. */
    fun findByCode(code: OrderCode): OrderQm?

    /**
     * Locks the order's row until the transaction ends and reads it (roadmap section 4.11, lock 9): taken by whatever
     * changes a paid order from the panel or the customer (a refund, a cancellation, shipping), before any other count is
     * made, so two such changes of one order run one after the other. Null when there is no such order.
     */
    fun lockOrder(orderId: OrderId): OrderQm?

    /**
     * When each of the customer's unpaid orders that still hold stock stops holding it: unpaid, and their time to pay
     * not over at [now]. What the limit of unpaid orders counts (PaymentPolicy.refusalToPlace).
     */
    fun unpaidHoldingUntil(userId: UserId, now: Instant): List<Instant>

    /**
     * The expiry job's candidates: unpaid orders whose time to pay is over at [now], the longest over first, at most
     * [limit]. Only candidates: the order itself decides, with its attempts locked (ExpireUnpaidOrderUc).
     */
    fun findExpirable(now: Instant, limit: Int): List<OrderId>

    /** The customer's orders, newest first; only those in [statuses], or all when null («سفارش‌های من» and its tabs). */
    fun listOfCustomer(userId: UserId, statuses: Set<OrderStatus>?, pageable: Pageable): Page<OrderQm>

    /** How many orders the customer has in each status, for the tabs' counts. A status they have none in is left out. */
    fun countByStatusOfCustomer(userId: UserId): Map<OrderStatus, Long>

    /**
     * What each of [userIds] bought, for the panel's customers: their orders the money came in for (OrderStatus.isPaid,
     * cancelled ones too), the money they kept (paid minus given back), and when the latest was placed. A customer who
     * bought nothing is left out.
     */
    fun purchasesOf(userIds: Collection<UserId>): Map<UserId, Purchases>

    /** The customer has a purchase that stands (OrderStatus.countsAsPurchase): no longer their first, for «خرید اول». */
    fun hasPurchase(userId: UserId): Boolean

    /** The orders held in [groupId] (RESERVED): what letting the group go releases. */
    fun findHeldIn(groupId: ReservationGroupId): List<OrderId>

    /**
     * The codes of the orders in each of [groupIds] that stand (OrderStatus.countsAsPurchase), in the order they were paid:
     * a reservation's `groupOrderCodes`. A cancelled order is no longer one of them.
     */
    fun paidCodesIn(groupIds: Collection<ReservationGroupId>): Map<ReservationGroupId, List<OrderCode>>

    /** @property totalSpent what was paid minus what went back. @property lastOrderOn when the latest of these orders was placed. */
    data class Purchases(val ordersCount: Int, val totalSpent: Money, val lastOrderOn: Instant)
}
