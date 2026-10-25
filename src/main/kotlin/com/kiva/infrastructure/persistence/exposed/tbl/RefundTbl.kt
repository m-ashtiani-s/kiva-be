package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Refunds, one row each. Created by V28__refunds.sql (Exposed never creates tables); keep both in step. */
object RefundTbl : LongIdTable(name = "refund_tbl", columnName = "pId") {
    val refundId = long("refundId").uniqueIndex()
    val orderId = long("orderId")
    val amount = long("amount")
    val method = enumerationByName<RefundMethod>("method", 16)
    val status = enumerationByName<RefundStatus>("status", 10)
    val refundSource = enumerationByName<RefundSource>("source", 16)
    val reason = varchar("reason", 300).nullable()
    val returnId = long("returnId").nullable()
    val expectedBy = timestampWithTimeZone("expectedBy")
    val referenceId = varchar("referenceId", 64).nullable()
    val statusNote = varchar("statusNote", 300).nullable()
    val createdOn = timestampWithTimeZone("createdOn")
    val createdBy = long("createdBy")
    val processingOn = timestampWithTimeZone("processingOn").nullable()
    val completedOn = timestampWithTimeZone("completedOn").nullable()
    val failedOn = timestampWithTimeZone("failedOn").nullable()
    val version = long("version")

    /** The sequence new refund ids come from. */
    const val ID_SEQUENCE = "refundid_seq"
}
