package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * The stock's hand changes, one row per StockAdjusted, with the stock on hand after it. Written only by
 * InventoryQmProjector. Created by V25__inventory_adjustments.sql (Exposed never creates tables); keep both in step.
 */
object InventoryAdjustmentQmTbl : LongIdTable(name = "inventoryadjustmentqm_tbl", columnName = "pId") {
    val eventId = javaUUID("eventId").uniqueIndex()
    val variantId = long("variantId")
    val productId = long("productId")
    val sku = varchar("sku", 80)
    val delta = integer("delta")
    val reason = varchar("reason", 20)
    val note = varchar("note", 300).nullable()
    val stockAfter = integer("stockAfter")
    val occurredOn = timestampWithTimeZone("occurredOn")
    val occurredByUserId = long("occurredByUserId")
}
