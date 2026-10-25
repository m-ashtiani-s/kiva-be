package com.kiva.infrastructure.persistence.exposed

import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import com.kiva.application.repository.AdminOrderFilter
import com.kiva.application.repository.AdminOrderQm
import com.kiva.application.repository.AdminOrderSearch
import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.repository.OrderTimelineKind
import com.kiva.application.repository.ResolutionDecision
import com.kiva.application.usecase.order.usecase.query.AdminOrderSorts
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefundTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReservationGroupTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
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

    override fun findByCode(code: OrderCode): OrderQm? =
        OrderQmTbl.selectAll().where { OrderQmTbl.code eq code.value }.singleOrNull()?.toOrderQm()

    override fun findByIds(ids: Collection<OrderId>): List<OrderQm> {
        if (ids.isEmpty()) return emptyList()
        return OrderQmTbl.selectAll().where { OrderQmTbl.orderId inList ids.map { it.value }.distinct() }.map { it.toOrderQm() }
    }

    override fun lockOrder(orderId: OrderId): OrderQm? =
        OrderQmTbl.selectAll().where { OrderQmTbl.orderId eq orderId.value }.forUpdate().singleOrNull()?.toOrderQm()

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

    override fun findSilentPhotos(now: Instant, limit: Int): List<OrderId> =
        OrderQmTbl.select(OrderQmTbl.orderId)
            .where { (OrderQmTbl.photoStatus eq PhotoStatus.SENT) and (OrderQmTbl.feedbackDeadline lessEq now.atOffset(ZoneOffset.UTC)) }
            .orderBy(OrderQmTbl.feedbackDeadline)
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

    override fun sentMediaCountOf(userId: UserId): Int {
        var total = 0
        TransactionManager.current().exec(
            "SELECT COALESCE(SUM(jsonb_array_length(photos -> 'sent')), 0) FROM orderqm_tbl WHERE \"userId\" = ?",
            listOf(LongColumnType() to userId.value),
        ) { rs -> if (rs.next()) total = rs.getInt(1) }
        return total
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
        // What was paid (placed, and for changes since) minus what went back.
        val spent = (OrderQmTbl.payable + OrderQmTbl.balancePaid - OrderQmTbl.refunded).sum()
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

    override fun hasPurchase(userId: UserId): Boolean =
        OrderQmTbl.select(OrderQmTbl.orderId)
            .where { (OrderQmTbl.userId eq userId.value) and (OrderQmTbl.status inList OrderStatus.entries.filter { it.countsAsPurchase }) }
            .limit(1)
            .any()

    override fun findForAdmin(filter: AdminOrderFilter, pageable: Pageable): Page<AdminOrderQm> =
        OrderQmTbl.join(ReservationGroupTbl, JoinType.LEFT, OrderQmTbl.reservationGroupId, ReservationGroupTbl.groupId)
            .select(OrderQmTbl.columns + ReservationGroupTbl.status + ReservationGroupTbl.expiresOn)
            .where { conditionOf(filter, statuses = filter.statuses ?: AdminOrderFilter.QUEUE_DEFAULT) }
            .paginate(pageable, ADMIN_SORTS, tiebreaker = OrderQmTbl.id) { row ->
                val holding = row.getOrNull(ReservationGroupTbl.status) == ReservationGroupStatus.HOLDING
                AdminOrderQm(row.toOrderQm(), row.getOrNull(ReservationGroupTbl.expiresOn)?.toInstant()?.takeIf { holding })
            }

    override fun recentOfCustomer(userId: UserId, limit: Int): List<AdminOrderQm> =
        OrderQmTbl.join(ReservationGroupTbl, JoinType.LEFT, OrderQmTbl.reservationGroupId, ReservationGroupTbl.groupId)
            .select(OrderQmTbl.columns + ReservationGroupTbl.status + ReservationGroupTbl.expiresOn)
            .where { OrderQmTbl.userId eq userId.value }
            .orderBy(OrderQmTbl.placedOn to SortOrder.DESC, OrderQmTbl.orderId to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                val holding = row.getOrNull(ReservationGroupTbl.status) == ReservationGroupStatus.HOLDING
                AdminOrderQm(row.toOrderQm(), row.getOrNull(ReservationGroupTbl.expiresOn)?.toInstant()?.takeIf { holding })
            }

    override fun statusCounts(filter: AdminOrderFilter): Map<OrderStatus, Long> {
        val count = OrderQmTbl.orderId.count()
        return OrderQmTbl.select(OrderQmTbl.status, count)
            .where { conditionOf(filter, statuses = null) }
            .groupBy(OrderQmTbl.status)
            .associate { it[OrderQmTbl.status] to it[count] }
    }

    override fun findInDailyList(from: Instant, until: Instant): List<OrderQm> =
        OrderQmTbl.selectAll()
            .where {
                OrderQmTbl.showInDailyList and (OrderQmTbl.status neq OrderStatus.CANCELLED) and
                    (OrderQmTbl.shippedOn greaterEq from.atOffset(ZoneOffset.UTC)) and (OrderQmTbl.shippedOn less until.atOffset(ZoneOffset.UTC))
            }
            .orderBy(OrderQmTbl.shippedOn to SortOrder.DESC, OrderQmTbl.orderId to SortOrder.ASC)
            .map { it.toOrderQm() }

    override fun findAllOfCustomer(userId: UserId): List<OrderQm> =
        OrderQmTbl.selectAll()
            .where { OrderQmTbl.userId eq userId.value }
            .orderBy(OrderQmTbl.orderId to SortOrder.ASC)
            .map { it.toOrderQm() }

    override fun findDeliveredOfCustomer(userId: UserId): List<OrderQm> =
        OrderQmTbl.selectAll()
            .where { (OrderQmTbl.userId eq userId.value) and OrderQmTbl.deliveredOn.isNotNull() and (OrderQmTbl.status neq OrderStatus.CANCELLED) }
            .orderBy(OrderQmTbl.deliveredOn to SortOrder.DESC, OrderQmTbl.orderId to SortOrder.DESC)
            .map { it.toOrderQm() }

    override fun findShippedOfCustomer(userId: UserId, limit: Int): List<OrderQm> =
        OrderQmTbl.selectAll()
            .where { (OrderQmTbl.userId eq userId.value) and OrderQmTbl.shippedOn.isNotNull() and (OrderQmTbl.status neq OrderStatus.CANCELLED) }
            .orderBy(OrderQmTbl.shippedOn to SortOrder.DESC, OrderQmTbl.orderId to SortOrder.DESC)
            .limit(limit)
            .map { it.toOrderQm() }

    override fun findOfGroup(groupId: ReservationGroupId): List<OrderQm> =
        OrderQmTbl.selectAll()
            .where { OrderQmTbl.reservationGroupId eq groupId.value }
            .orderBy(OrderQmTbl.paidOn to SortOrder.ASC_NULLS_LAST, OrderQmTbl.orderId to SortOrder.ASC)
            .map { it.toOrderQm() }

    /**
     * Everything [filter] asks for, the statuses only when [statuses] is given (the tabs' counts take none). A `%` or `_`
     * typed is taken as itself. Words are looked for in searchText and in the account's names, lower-cased.
     */
    private fun conditionOf(filter: AdminOrderFilter, statuses: Set<OrderStatus>?): Op<Boolean> {
        val conditions = buildList<Op<Boolean>> {
            statuses?.let { add(OrderQmTbl.status inList it) }
            when (val search = filter.search) {
                null -> {}
                is AdminOrderSearch.Code -> add(OrderQmTbl.code eq search.code.value)
                is AdminOrderSearch.Phone -> add(
                    (OrderQmTbl.userId inSubQuery UserTbl.select(UserTbl.id).where { UserTbl.phone eq search.phone.value }) or
                        (OrderQmTbl.searchText like containing(" ${search.phone.value} ")),
                )
                is AdminOrderSearch.Words -> search.words.forEach { word ->
                    val pattern = containing(word)
                    val accounts = UserTbl.select(UserTbl.id).where { (UserTbl.firstName.lowerCase() like pattern) or (UserTbl.lastName.lowerCase() like pattern) }
                    add((OrderQmTbl.searchText.lowerCase() like pattern) or (OrderQmTbl.userId inSubQuery accounts))
                }
            }
            filter.shippingMethod?.let { add(OrderQmTbl.shippingMethod eq it) }
            filter.reserved?.let { add(if (it) OrderQmTbl.reservationGroupId.isNotNull() else OrderQmTbl.reservationGroupId.isNull()) }
            filter.awaitingPhotos?.let {
                val awaiting = (OrderQmTbl.status eq OrderStatus.PROCESSING) and (OrderQmTbl.photoStatus eq PhotoStatus.WAITING)
                add(if (it) awaiting else not(awaiting))
            }
            filter.refundStatus?.let { status ->
                add(OrderQmTbl.orderId inSubQuery RefundTbl.select(RefundTbl.orderId).where { RefundTbl.status eq status })
            }
            filter.placedFrom?.let { add(OrderQmTbl.placedOn greaterEq it.atOffset(ZoneOffset.UTC)) }
            filter.placedBefore?.let { add(OrderQmTbl.placedOn less it.atOffset(ZoneOffset.UTC)) }
        }
        return conditions.reduceOrNull { all, next -> all and next } ?: Op.TRUE
    }

    private fun containing(text: String): LikePattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(text, ESCAPE) + "%"

    override fun findHeldIn(groupId: ReservationGroupId): List<OrderId> =
        OrderQmTbl.select(OrderQmTbl.orderId)
            .where { (OrderQmTbl.reservationGroupId eq groupId.value) and (OrderQmTbl.status eq OrderStatus.RESERVED) }
            .orderBy(OrderQmTbl.orderId)
            .map { OrderId.constructFromPersisted(it[OrderQmTbl.orderId]) }

    override fun paidCodesIn(groupIds: Collection<ReservationGroupId>): Map<ReservationGroupId, List<OrderCode>> {
        if (groupIds.isEmpty()) return emptyMap()
        return OrderQmTbl.select(OrderQmTbl.reservationGroupId, OrderQmTbl.code)
            .where { (OrderQmTbl.reservationGroupId inList groupIds.map { it.value }) and (OrderQmTbl.status inList OrderStatus.entries.filter { it.countsAsPurchase }) }
            .orderBy(OrderQmTbl.paidOn to SortOrder.ASC, OrderQmTbl.orderId to SortOrder.ASC)
            .groupBy({ ReservationGroupId.constructFromPersisted(it[OrderQmTbl.reservationGroupId]!!) }, { OrderCode.constructFromPersisted(it[OrderQmTbl.code]) })
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
        reservationGroupId = this[OrderQmTbl.reservationGroupId]?.let { ReservationGroupId.constructFromPersisted(it) },
        lines = this[OrderQmTbl.lines].map { it.toDomain() },
        address = this[OrderQmTbl.address].toDomain(),
        messenger = this[OrderQmTbl.messenger].toDomain(),
        totals = this[OrderQmTbl.totals].toDomain(),
        timeline = this[OrderQmTbl.timeline].map { entry ->
            OrderQm.TimelineEntry(
                kind = OrderTimelineKind.valueOf(entry.kind),
                status = OrderStatus.valueOf(entry.status),
                at = Instant.parse(entry.at),
                failureReason = entry.failureReason?.let { PaymentFailureReason.valueOf(it) },
                note = entry.note,
                amount = entry.amount?.let { Money.constructFromPersisted(it) },
            )
        },
        customerNote = this[OrderQmTbl.customerNote]?.let { CustomerNote.constructFromPersisted(it) },
        gift = this[OrderQmTbl.gift]?.toDomain(),
        photoStatus = this[OrderQmTbl.photoStatus],
        photoEverSent = this[OrderQmTbl.photoEverSent],
        photoDraft = this[OrderQmTbl.photos].draft.map { it.toDomain() },
        photosSent = this[OrderQmTbl.photos].sent.map { it.toDomain() },
        photoBatch = this[OrderQmTbl.photos].batch,
        photosSentOn = this[OrderQmTbl.photosSentOn]?.toInstant(),
        feedbackDeadline = this[OrderQmTbl.feedbackDeadline]?.toInstant(),
        feedback = this[OrderQmTbl.feedback]?.let { answer ->
            OrderQm.Feedback(
                decision = FeedbackDecision.valueOf(answer.decision),
                at = Instant.parse(answer.at),
                changeType = answer.changeType?.let(ChangeType::valueOf),
                lineId = answer.orderItemId,
                desiredVariantId = answer.desiredVariantId,
                note = answer.note,
                resolution = answer.resolution?.let { OrderQm.Resolution(ResolutionDecision.valueOf(it.decision), it.variantId, it.note, Instant.parse(it.at)) },
            )
        },
        shipment = this[OrderQmTbl.trackingCode]?.let { code ->
            OrderQm.Shipment(TrackingCode.constructFromPersisted(code), checkNotNull(this[OrderQmTbl.shippedOn]).toInstant(), this[OrderQmTbl.showInDailyList])
        },
        deliveredOn = this[OrderQmTbl.deliveredOn]?.toInstant(),
        returnableUntil = this[OrderQmTbl.returnableUntil]?.toInstant(),
        cancellation = this[OrderQmTbl.cancellation]?.let {
            OrderQm.Cancellation(
                CancelledBy.valueOf(it.by), it.reason?.let(CancelReason::valueOf), it.note, checkNotNull(this[OrderQmTbl.cancelledOn]).toInstant(),
            )
        },
        refunded = Money.constructFromPersisted(this[OrderQmTbl.refunded]),
        balanceDue = Money.constructFromPersisted(this[OrderQmTbl.balanceDue]),
        balancePaid = Money.constructFromPersisted(this[OrderQmTbl.balancePaid]),
        codeDiscountRemoved = Money.constructFromPersisted(this[OrderQmTbl.codeDiscountRemoved]),
        returnSettled = this[OrderQmTbl.returnSettled],
        openReturnId = this[OrderQmTbl.openReturnId]?.let { ReturnId.constructFromPersisted(it) },
    )

    private companion object {
        const val ESCAPE = '\\'

        // The id breaks ties of orders placed in the same instant, the same way.
        val ADMIN_SORTS: SortOrders = mapOf(
            AdminOrderSorts.NEWEST to listOf(OrderQmTbl.placedOn to SortOrder.DESC, OrderQmTbl.orderId to SortOrder.DESC),
            AdminOrderSorts.OLDEST to listOf(OrderQmTbl.placedOn to SortOrder.ASC, OrderQmTbl.orderId to SortOrder.ASC),
        )
    }
}
