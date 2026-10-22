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

    /** The orders of [ids] that exist, in no particular order: a page of returns gets its orders' lines in one read. */
    fun findByIds(ids: Collection<OrderId>): List<OrderQm>

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

    /**
     * The approve-silent-photos job's candidates: orders whose photos were sent and wait for an answer past their
     * deadline at [now], the longest past first, at most [limit]. Only candidates: the order itself decides
     * (ApproveSilentPhotosUc).
     */
    fun findSilentPhotos(now: Instant, limit: Int): List<OrderId>

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

    /**
     * The panel's queue: the orders [filter] lets through, newest placed first or oldest (the pageable's sort, AdminOrderSorts), each with
     * its reservation group's end while the group still holds.
     */
    fun findForAdmin(filter: AdminOrderFilter, pageable: Pageable): Page<AdminOrderQm>

    /**
     * The last [limit] orders of [userId] in any status (unpaid and expired too, so support sees them), newest placed
     * first, each with its reservation group's end while the group still holds: a customer's page in the panel.
     */
    fun recentOfCustomer(userId: UserId, limit: Int): List<AdminOrderQm>

    /** How many orders [filter] lets through in each status, every status there (zero too); the filter's statuses are ignored. */
    fun statusCounts(filter: AdminOrderFilter): Map<OrderStatus, Long>

    /**
     * The parcels of the public daily list: orders shipped from [from] (inclusive) to [until] (exclusive) with
     * `showInDailyList`, the latest shipped first. A group's orders share one tracking code: the caller makes them one row.
     */
    fun findInDailyList(from: Instant, until: Instant): List<OrderQm>

    /** The customer's delivered orders that weren't cancelled since, the latest delivered first: what a review may be linked to. */
    fun findDeliveredOfCustomer(userId: UserId): List<OrderQm>

    /** The customer's shipped orders that weren't cancelled, the latest shipped first, at most [limit]. */
    fun findShippedOfCustomer(userId: UserId, limit: Int): List<OrderQm>

    /** Every order of [groupId], in the order they were paid (unpaid ones last). */
    fun findOfGroup(groupId: ReservationGroupId): List<OrderQm>

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

/**
 * One order of the panel's queue, with what its row shows of its reservation group.
 *
 * @property groupHoldsUntil the group's end while it still holds its orders (HOLDING); null otherwise.
 */
data class AdminOrderQm(val order: OrderQm, val groupHoldsUntil: Instant?) {
    override fun toString(): String = "AdminOrderQm(${order.code.value})"
}
