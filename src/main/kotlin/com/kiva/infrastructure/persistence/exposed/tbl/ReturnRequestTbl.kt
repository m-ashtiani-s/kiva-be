package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** Returns, one row each. Created by V32__returns.sql (Exposed never creates tables); keep both in step. */
object ReturnRequestTbl : LongIdTable(name = "returnrequest_tbl", columnName = "pId") {
    val returnId = long("returnId").uniqueIndex()
    val code = varchar("code", 16).uniqueIndex()
    val orderId = long("orderId")
    val orderCode = varchar("orderCode", 16)
    val userId = long("userId")
    val status = enumerationByName<ReturnStatus>("status", 20)
    val reason = enumerationByName<ReturnReason>("reason", 24)
    val description = varchar("description", 1000).nullable()
    val items = jsonb<List<ReturnItemQm>>("items", Json)
    val mediaIds = jsonb<List<String>>("mediaIds", Json)
    val refundMethod = enumerationByName<RefundMethod>("refundMethod", 16)
    val shippingPaidBy = enumerationByName<ReturnShippingPayer>("shippingPaidBy", 8)
    val instructions = varchar("instructions", 1000).nullable()
    val rejectionReason = varchar("rejectionReason", 500).nullable()
    val pickupCarrier = enumerationByName<ShippingMethodCode>("pickupCarrier", 8).nullable()
    val pickupAt = timestampWithTimeZone("pickupAt").nullable()
    val pickupTracking = varchar("pickupTracking", 40).nullable()
    val pickupNote = varchar("pickupNote", 500).nullable()
    val receipt = jsonb<ReturnReceiptQm>("receipt", Json).nullable()
    val closeReason = varchar("closeReason", 500).nullable()
    val refundId = long("refundId").nullable()
    val createdOn = timestampWithTimeZone("createdOn")
    val decidedOn = timestampWithTimeZone("decidedOn").nullable()
    val decidedBy = long("decidedBy").nullable()
    val receivedOn = timestampWithTimeZone("receivedOn").nullable()
    val receivedBy = long("receivedBy").nullable()
    val closedOn = timestampWithTimeZone("closedOn").nullable()
    val refundedOn = timestampWithTimeZone("refundedOn").nullable()
    val version = long("version")

    /** The sequence new return ids come from. */
    const val ID_SEQUENCE = "returnrequestid_seq"
}

/** One line of a return: the order's line and how many of it go back. */
@Serializable
data class ReturnItemQm(val lineId: Int, val quantity: Int)

/** What came back once the parcel was checked; [note] is internal, never logged. */
@Serializable
data class ReturnReceiptQm(val lines: List<ReceiptLineQm>, val note: String?) {
    override fun toString(): String = "ReturnReceiptQm(lines=$lines)"
}

@Serializable
data class ReceiptLineQm(val lineId: Int, val received: Int, val restock: Int)
