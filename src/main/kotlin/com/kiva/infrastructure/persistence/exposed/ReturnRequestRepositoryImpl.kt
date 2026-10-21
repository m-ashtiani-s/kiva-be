package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReceiptLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReceipt
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnIdGenerator
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.infrastructure.persistence.exposed.tbl.ReceiptLineQm
import com.kiva.infrastructure.persistence.exposed.tbl.ReturnItemQm
import com.kiva.infrastructure.persistence.exposed.tbl.ReturnReceiptQm
import com.kiva.infrastructure.persistence.exposed.tbl.ReturnRequestTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * returnrequest_tbl. Runs inside the use case's transaction and never opens one itself. A change is written only over the
 * version it was read with ([save]); the lock is a row lock (`FOR UPDATE`), taken after the order's row (lock 9).
 */
class ReturnRequestRepositoryImpl : ReturnRequestRepository {

    override fun add(returnRequest: ReturnRequest) {
        ReturnRequestTbl.insert {
            it[returnId] = returnRequest.id.value
            it[code] = returnRequest.code.value
            it[orderId] = returnRequest.orderId.value
            it[orderCode] = returnRequest.orderCode.value
            it[userId] = returnRequest.userId.value
            it[reason] = returnRequest.reason
            it[description] = returnRequest.description?.value
            it[items] = returnRequest.items.map { item -> ReturnItemQm(item.lineId, item.quantity) }
            it[mediaIds] = returnRequest.mediaIds.map(MediaId::value)
            it[refundMethod] = returnRequest.refundMethod
            it[createdOn] = returnRequest.createdOn.utc()
            it[version] = returnRequest.version
            changes(returnRequest, it)
        }
    }

    override fun find(id: ReturnId): ReturnRequest? =
        ReturnRequestTbl.selectAll().where { ReturnRequestTbl.returnId eq id.value }.singleOrNull()?.toReturnRequest()

    override fun lock(id: ReturnId): ReturnRequest? =
        ReturnRequestTbl.selectAll().where { ReturnRequestTbl.returnId eq id.value }.forUpdate().singleOrNull()?.toReturnRequest()

    override fun findOfOrder(orderId: OrderId): List<ReturnRequest> =
        ReturnRequestTbl.selectAll().where { ReturnRequestTbl.orderId eq orderId.value }.orderBy(ReturnRequestTbl.returnId to SortOrder.DESC).map { it.toReturnRequest() }

    override fun findOfUser(userId: UserId, limit: Int): List<ReturnRequest> =
        ReturnRequestTbl.selectAll()
            .where { ReturnRequestTbl.userId eq userId.value }
            .orderBy(ReturnRequestTbl.createdOn to SortOrder.DESC, ReturnRequestTbl.returnId to SortOrder.DESC)
            .limit(limit)
            .map { it.toReturnRequest() }

    override fun save(returnRequest: ReturnRequest) {
        val updated = ReturnRequestTbl.update({ (ReturnRequestTbl.returnId eq returnRequest.id.value) and (ReturnRequestTbl.version eq returnRequest.version) }) {
            changes(returnRequest, it)
            it[version] = returnRequest.version + 1
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(returnRequest.id.value.toString()))
    }

    /** What a step may change: everything but what the customer asked. */
    private fun changes(ret: ReturnRequest, it: UpdateBuilder<*>) {
        it[ReturnRequestTbl.status] = ret.status
        it[ReturnRequestTbl.shippingPaidBy] = ret.shippingPaidBy
        it[ReturnRequestTbl.instructions] = ret.instructions?.value
        it[ReturnRequestTbl.rejectionReason] = ret.rejectionReason?.value
        it[ReturnRequestTbl.pickupCarrier] = ret.pickup?.carrier
        it[ReturnRequestTbl.pickupAt] = ret.pickup?.scheduledFor?.utc()
        it[ReturnRequestTbl.pickupTracking] = ret.pickup?.trackingCode?.value
        it[ReturnRequestTbl.pickupNote] = ret.pickup?.note?.value
        it[ReturnRequestTbl.receipt] = ret.receipt?.let { receipt ->
            ReturnReceiptQm(receipt.lines.map { line -> ReceiptLineQm(line.lineId, line.received, line.restock) }, receipt.note?.value)
        }
        it[ReturnRequestTbl.closeReason] = ret.closeReason?.value
        it[ReturnRequestTbl.refundId] = ret.refundId?.value
        it[ReturnRequestTbl.decidedOn] = ret.decidedOn?.utc()
        it[ReturnRequestTbl.decidedBy] = ret.decidedBy?.value
        it[ReturnRequestTbl.receivedOn] = ret.receivedOn?.utc()
        it[ReturnRequestTbl.receivedBy] = ret.receivedBy?.value
        it[ReturnRequestTbl.closedOn] = ret.closedOn?.utc()
        it[ReturnRequestTbl.refundedOn] = ret.refundedOn?.utc()
    }

    private fun ResultRow.toReturnRequest(): ReturnRequest = ReturnRequest.constructFromPersisted(
        id = ReturnId.constructFromPersisted(this[ReturnRequestTbl.returnId]),
        orderId = OrderId.constructFromPersisted(this[ReturnRequestTbl.orderId]),
        orderCode = OrderCode.constructFromPersisted(this[ReturnRequestTbl.orderCode]),
        userId = UserId.constructFromPersisted(this[ReturnRequestTbl.userId]),
        status = this[ReturnRequestTbl.status],
        reason = this[ReturnRequestTbl.reason],
        description = this[ReturnRequestTbl.description]?.let { ReturnText.constructFromPersisted(it) },
        items = this[ReturnRequestTbl.items].map { ReturnItem.constructFromPersisted(it.lineId, it.quantity) },
        mediaIds = this[ReturnRequestTbl.mediaIds].map { MediaId.constructFromPersisted(it) },
        refundMethod = this[ReturnRequestTbl.refundMethod],
        shippingPaidBy = this[ReturnRequestTbl.shippingPaidBy],
        instructions = this[ReturnRequestTbl.instructions]?.let { ReturnText.constructFromPersisted(it) },
        rejectionReason = this[ReturnRequestTbl.rejectionReason]?.let { ReturnText.constructFromPersisted(it) },
        pickup = this[ReturnRequestTbl.pickupCarrier]?.let { carrier ->
            ReturnPickup.constructFromPersisted(
                carrier,
                this[ReturnRequestTbl.pickupAt]?.toInstant(),
                this[ReturnRequestTbl.pickupTracking]?.let { TrackingCode.constructFromPersisted(it) },
                this[ReturnRequestTbl.pickupNote]?.let { ReturnText.constructFromPersisted(it) },
            )
        },
        receipt = this[ReturnRequestTbl.receipt]?.let { receipt ->
            ReturnReceipt.constructFromPersisted(
                receipt.lines.map { ReceiptLine.constructFromPersisted(it.lineId, it.received, it.restock) },
                receipt.note?.let { ReturnText.constructFromPersisted(it) },
            )
        },
        closeReason = this[ReturnRequestTbl.closeReason]?.let { ReturnText.constructFromPersisted(it) },
        refundId = this[ReturnRequestTbl.refundId]?.let { RefundId.constructFromPersisted(it) },
        createdOn = this[ReturnRequestTbl.createdOn].toInstant(),
        decidedOn = this[ReturnRequestTbl.decidedOn]?.toInstant(),
        decidedBy = this[ReturnRequestTbl.decidedBy]?.let { UserId.constructFromPersisted(it) },
        receivedOn = this[ReturnRequestTbl.receivedOn]?.toInstant(),
        receivedBy = this[ReturnRequestTbl.receivedBy]?.let { UserId.constructFromPersisted(it) },
        closedOn = this[ReturnRequestTbl.closedOn]?.toInstant(),
        refundedOn = this[ReturnRequestTbl.refundedOn]?.toInstant(),
        version = this[ReturnRequestTbl.version],
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("RETURN_REQUEST")
    }
}

/** Takes the next number from the sequence of V32__returns.sql. Runs inside the use case's transaction. */
class ReturnIdGeneratorImpl : ReturnIdGenerator {
    override fun nextReturnId(): ReturnId =
        TransactionManager.current().exec("SELECT nextval('${ReturnRequestTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            ReturnId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${ReturnRequestTbl.ID_SEQUENCE}') returned nothing")
}
