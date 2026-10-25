package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.BankReference
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundNote
import com.kiva.domain.aggregate.refund.model.valueobject.RefundReason
import com.kiva.domain.aggregate.refund.repository.RefundIdGenerator
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.infrastructure.persistence.exposed.tbl.RefundTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * refund_tbl. Runs inside the use case's transaction and never opens one itself. A change is written only over the
 * version it was read with ([save]); the locks are row locks (`FOR UPDATE`), taken after the order's row (lock 9).
 */
class RefundRepositoryImpl : RefundRepository {

    override fun add(refund: Refund) {
        RefundTbl.insert {
            it[refundId] = refund.id.value
            it[orderId] = refund.orderId.value
            it[amount] = refund.amount.toman
            it[method] = refund.method
            it[status] = refund.status
            it[refundSource] = refund.source
            it[reason] = refund.reason?.value
            it[returnId] = refund.returnId
            it[expectedBy] = refund.expectedBy.utc()
            it[referenceId] = refund.referenceId?.value
            it[statusNote] = refund.statusNote?.value
            it[createdOn] = refund.createdOn.utc()
            it[createdBy] = refund.createdBy.value
            it[processingOn] = refund.processingOn?.utc()
            it[completedOn] = refund.completedOn?.utc()
            it[failedOn] = refund.failedOn?.utc()
            it[version] = refund.version
        }
    }

    override fun findOrderOf(id: RefundId): OrderId? =
        RefundTbl.select(RefundTbl.orderId).where { RefundTbl.refundId eq id.value }.singleOrNull()?.let { OrderId.constructFromPersisted(it[RefundTbl.orderId]) }

    override fun lock(id: RefundId): Refund? =
        RefundTbl.selectAll().where { RefundTbl.refundId eq id.value }.forUpdate().singleOrNull()?.toRefund()

    override fun lockAllOf(orderId: OrderId): List<Refund> =
        RefundTbl.selectAll().where { RefundTbl.orderId eq orderId.value }.orderBy(RefundTbl.refundId to SortOrder.ASC).forUpdate().map { it.toRefund() }

    override fun findAllOf(orderId: OrderId): List<Refund> =
        RefundTbl.selectAll().where { RefundTbl.orderId eq orderId.value }.orderBy(RefundTbl.refundId to SortOrder.ASC).map { it.toRefund() }

    override fun findAllOfOrders(orderIds: Collection<OrderId>): List<Refund> {
        if (orderIds.isEmpty()) return emptyList()
        return RefundTbl.selectAll().where { RefundTbl.orderId inList orderIds.map { it.value } }.orderBy(RefundTbl.refundId to SortOrder.ASC).map { it.toRefund() }
    }

    override fun findAllOfReturns(returnIds: Collection<ReturnId>): List<Refund> {
        if (returnIds.isEmpty()) return emptyList()
        return RefundTbl.selectAll().where { RefundTbl.returnId inList returnIds.map { it.value } }.orderBy(RefundTbl.refundId to SortOrder.ASC).map { it.toRefund() }
    }

    override fun save(refund: Refund) {
        val updated = RefundTbl.update({ (RefundTbl.refundId eq refund.id.value) and (RefundTbl.version eq refund.version) }) {
            it[status] = refund.status
            it[referenceId] = refund.referenceId?.value
            it[statusNote] = refund.statusNote?.value
            it[processingOn] = refund.processingOn?.utc()
            it[completedOn] = refund.completedOn?.utc()
            it[failedOn] = refund.failedOn?.utc()
            it[version] = refund.version + 1
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(refund.id.value.toString()))
    }

    private fun ResultRow.toRefund(): Refund = Refund.constructFromPersisted(
        id = RefundId.constructFromPersisted(this[RefundTbl.refundId]),
        orderId = OrderId.constructFromPersisted(this[RefundTbl.orderId]),
        amount = Money.constructFromPersisted(this[RefundTbl.amount]),
        method = this[RefundTbl.method],
        status = this[RefundTbl.status],
        source = this[RefundTbl.refundSource],
        reason = this[RefundTbl.reason]?.let { RefundReason.constructFromPersisted(it) },
        returnId = this[RefundTbl.returnId],
        expectedBy = this[RefundTbl.expectedBy].toInstant(),
        referenceId = this[RefundTbl.referenceId]?.let { BankReference.constructFromPersisted(it) },
        statusNote = this[RefundTbl.statusNote]?.let { RefundNote.constructFromPersisted(it) },
        createdOn = this[RefundTbl.createdOn].toInstant(),
        createdBy = UserId.constructFromPersisted(this[RefundTbl.createdBy]),
        processingOn = this[RefundTbl.processingOn]?.toInstant(),
        completedOn = this[RefundTbl.completedOn]?.toInstant(),
        failedOn = this[RefundTbl.failedOn]?.toInstant(),
        version = this[RefundTbl.version],
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("REFUND")
    }
}

/** Takes the next number from the sequence of V28__refunds.sql. Runs inside the use case's transaction. */
class RefundIdGeneratorImpl : RefundIdGenerator {
    override fun nextRefundId(): RefundId =
        TransactionManager.current().exec("SELECT nextval('${RefundTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            RefundId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${RefundTbl.ID_SEQUENCE}') returned nothing")
}
