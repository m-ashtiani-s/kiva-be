package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.ZoneOffset

/** Reads orderqm_tbl. Runs inside the use case's transaction; it never opens one itself. */
class OrderQmRepositoryImpl : OrderQmRepository {

    override fun findOfCustomer(userId: UserId, code: OrderCode): OrderQm? =
        OrderQmTbl.selectAll()
            .where { (OrderQmTbl.userId eq userId.value) and (OrderQmTbl.code eq code.value) }
            .singleOrNull()
            ?.toOrderQm()

    override fun unpaidHoldingUntil(userId: UserId, now: Instant): List<Instant> =
        OrderQmTbl.select(OrderQmTbl.holdUntil)
            .where {
                (OrderQmTbl.userId eq userId.value) and
                    (OrderQmTbl.status inList OrderStatus.entries.filter { it.isAwaitingPayment }) and
                    (OrderQmTbl.holdUntil greater now.atOffset(ZoneOffset.UTC))
            }
            .map { it[OrderQmTbl.holdUntil].toInstant() }

    override fun findExpirable(now: Instant, limit: Int): List<OrderId> =
        OrderQmTbl.select(OrderQmTbl.orderId)
            .where { (OrderQmTbl.status inList OrderStatus.entries.filter { it.isAwaitingPayment }) and (OrderQmTbl.holdUntil lessEq now.atOffset(ZoneOffset.UTC)) }
            .orderBy(OrderQmTbl.holdUntil)
            .limit(limit)
            .map { OrderId.constructFromPersisted(it[OrderQmTbl.orderId]) }

    /** Always newest first (the list has no other order); the order id breaks a tie, so a page never repeats an order. */
    override fun listOfCustomer(userId: UserId, statuses: Set<OrderStatus>?, pageable: Pageable): Page<OrderQm> {
        val query = OrderQmTbl.selectAll()
            .where { (OrderQmTbl.userId eq userId.value) and (statuses?.let { OrderQmTbl.status inList it } ?: Op.TRUE) }
        val total = query.copy().count()
        val items = query.orderBy(OrderQmTbl.placedOn to SortOrder.DESC, OrderQmTbl.orderId to SortOrder.DESC)
            .limit(pageable.size)
            .offset(pageable.offset)
            .map { it.toOrderQm() }
        return Page(items, pageable.page, pageable.size, total)
    }

    override fun countByStatusOfCustomer(userId: UserId): Map<OrderStatus, Long> {
        val count = OrderQmTbl.orderId.count()
        return OrderQmTbl.select(OrderQmTbl.status, count)
            .where { OrderQmTbl.userId eq userId.value }
            .groupBy(OrderQmTbl.status)
            .associate { it[OrderQmTbl.status] to it[count] }
    }

    override fun purchasesOf(userIds: Collection<UserId>): Map<UserId, OrderQmRepository.Purchases> {
        if (userIds.isEmpty()) return emptyMap()
        val count = OrderQmTbl.orderId.count()
        val spent = OrderQmTbl.payable.sum()
        val latest = OrderQmTbl.placedOn.max()
        return OrderQmTbl.select(OrderQmTbl.userId, count, spent, latest)
            .where { (OrderQmTbl.userId inList userIds.map { it.value }) and (OrderQmTbl.status inList OrderStatus.entries.filter { it.isPaid }) }
            .groupBy(OrderQmTbl.userId)
            .associate { row ->
                UserId.constructFromPersisted(row[OrderQmTbl.userId]) to OrderQmRepository.Purchases(
                    ordersCount = Math.toIntExact(row[count]),
                    totalSpent = Money.constructFromPersisted(row[spent] ?: 0),
                    lastOrderOn = checkNotNull(row[latest]).toInstant(),
                )
            }
    }

    private fun ResultRow.toOrderQm(): OrderQm = OrderQm(
        orderId = OrderId.constructFromPersisted(this[OrderQmTbl.orderId]),
        code = OrderCode.constructFromPersisted(this[OrderQmTbl.code]),
        userId = UserId.constructFromPersisted(this[OrderQmTbl.userId]),
        status = this[OrderQmTbl.status],
        placedOn = this[OrderQmTbl.placedOn].toInstant(),
        paidOn = this[OrderQmTbl.paidOn]?.toInstant(),
        holdUntil = this[OrderQmTbl.holdUntil].toInstant(),
        shippingMethod = this[OrderQmTbl.shippingMethod],
        reserveRequested = this[OrderQmTbl.reserveRequested],
        lines = this[OrderQmTbl.lines].map { it.toDomain() },
        address = this[OrderQmTbl.address].toDomain(),
        messenger = this[OrderQmTbl.messenger].toDomain(),
        totals = this[OrderQmTbl.totals].toDomain(),
        timeline = this[OrderQmTbl.timeline].map { entry ->
            OrderQm.TimelineEntry(
                status = OrderStatus.valueOf(entry.status),
                at = Instant.parse(entry.at),
                failureReason = entry.failureReason?.let { PaymentFailureReason.valueOf(it) },
            )
        },
        customerNote = this[OrderQmTbl.customerNote]?.let { CustomerNote.constructFromPersisted(it) },
        gift = this[OrderQmTbl.gift]?.toDomain(),
    )
}
