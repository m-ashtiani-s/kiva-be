package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** Reads orderqm_tbl. Runs inside the use case's transaction; it never opens one itself. */
class OrderQmRepositoryImpl : OrderQmRepository {

    override fun findOfCustomer(userId: UserId, code: OrderCode): OrderQm? =
        OrderQmTbl.selectAll()
            .where { (OrderQmTbl.userId eq userId.value) and (OrderQmTbl.code eq code.value) }
            .singleOrNull()
            ?.toOrderQm()

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
