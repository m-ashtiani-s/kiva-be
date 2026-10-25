package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Discount codes. Created by V20__discount_codes.sql and V30__admin_settings.sql (Exposed never creates tables); keep them in step. */
object DiscountCodeTbl : LongIdTable(name = "discountcode_tbl", columnName = "pId") {
    /** The unique key on the code's text (V20's inline UNIQUE). */
    const val CODE_KEY = "discountcode_tbl_code_key"

    /** The identity column's sequence: a new code's id is taken from it before the insert. */
    const val ID_SEQUENCE_OF = "pg_get_serial_sequence('discountcode_tbl', 'pId')"

    val code = varchar("code", 32).uniqueIndex()
    val type = enumerationByName<DiscountType>("type", 7)
    val value = integer("value")
    val label = varchar("label", 200)
    val minSubtotal = long("minSubtotal").nullable()
    val maxDiscount = long("maxDiscount").nullable()
    val startsAt = timestampWithTimeZone("startsAt").nullable()
    val endsAt = timestampWithTimeZone("endsAt").nullable()
    val usageLimit = integer("usageLimit").nullable()
    val perUserLimit = integer("perUserLimit").nullable()
    val firstOrderOnly = bool("firstOrderOnly").default(false)
    val requiresLogin = bool("requiresLogin").default(false)
    val excludeSaleItems = bool("excludeSaleItems").default(false)
    val categoryIds = array<Long>("categoryIds")
    val productIds = array<Long>("productIds")
    val isActive = bool("isActive").default(true)
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
    val version = long("version")
}

/** Each order's use of a discount code. Created by V20__discount_codes.sql. */
object DiscountRedemptionTbl : LongIdTable(name = "discountredemption_tbl", columnName = "pId") {
    val codeId = long("codeId").references(DiscountCodeTbl.id)
    val userId = long("userId")
    val orderId = long("orderId").uniqueIndex()
    val amount = long("amount")
    val status = enumerationByName<DiscountRedemptionStatus>("status", 9)
    val heldOn = timestampWithTimeZone("heldOn")
    val settledOn = timestampWithTimeZone("settledOn").nullable()
}
