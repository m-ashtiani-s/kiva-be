package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** «موجود شد خبرم کن» subscriptions, a row each. Created by V35__stock_alerts.sql (Exposed never creates tables); keep both in step. */
object StockAlertTbl : LongIdTable(name = "stockalert_tbl", columnName = "pId") {
    val alertId = long("alertId").uniqueIndex()
    val userId = long("userId")
    val productId = long("productId")
    val variantId = long("variantId").nullable()
    val status = enumerationByName<StockAlertStatus>("status", 10)
    val createdOn = timestampWithTimeZone("createdOn")
    val notifiedOn = timestampWithTimeZone("notifiedOn").nullable()
    val notifiedVariantId = long("notifiedVariantId").nullable()
    val cancelledOn = timestampWithTimeZone("cancelledOn").nullable()

    const val ID_SEQUENCE = "stockalertid_seq"
    const val ONE_ACTIVE_KEY = "stockalert_one_active_idx"
}
