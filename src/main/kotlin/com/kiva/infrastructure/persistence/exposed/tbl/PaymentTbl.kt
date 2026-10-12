package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** Payment attempts. Created by V16__payments.sql (Exposed never creates tables); keep both in step. */
object PaymentTbl : LongIdTable(name = "payment_tbl", columnName = "pId") {
    val publicId = varchar("publicId", 16).uniqueIndex()
    val orderId = long("orderId")
    val userId = long("userId")
    val gateway = enumerationByName<PaymentGatewayCode>("gateway", 10)
    val amount = long("amount")
    val status = enumerationByName<PaymentStatus>("status", 15)
    val authority = varchar("authority", 100).nullable()
    val redirectUrl = varchar("redirectUrl", 500).nullable()
    val redirectMethod = enumerationByName<PaymentRedirect.Method>("redirectMethod", 4).nullable()
    val redirectFields = jsonb<Map<String, String>>("redirectFields", Json).nullable()
    val refId = varchar("refId", 64).nullable()
    val cardMask = varchar("cardMask", 32).nullable()
    val failureReason = enumerationByName<PaymentFailureReason>("failureReason", 30).nullable()
    val createdOn = timestampWithTimeZone("createdOn")
    val redirectedOn = timestampWithTimeZone("redirectedOn").nullable()
    val verifyingSince = timestampWithTimeZone("verifyingSince").nullable()
    val settledOn = timestampWithTimeZone("settledOn").nullable()
    val version = long("version").default(0)
}

/** The log of every exchange with a gateway; append-only (a trigger refuses UPDATE and DELETE). Created by V16__payments.sql. */
object PaymentLogTbl : LongIdTable(name = "paymentlog_tbl", columnName = "pId") {
    val paymentId = long("paymentId").references(PaymentTbl.id)
    val occurredOn = timestampWithTimeZone("occurredOn")
    val kind = enumerationByName<PaymentLogEntry.Kind>("kind", 10)
    val outcome = varchar("outcome", 30)
    val gatewayCode = integer("gatewayCode").nullable()
    val httpStatus = integer("httpStatus").nullable()
    val durationMs = integer("durationMs").nullable()
}
