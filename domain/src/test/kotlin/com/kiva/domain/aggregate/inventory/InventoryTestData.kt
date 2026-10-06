package com.kiva.domain.aggregate.inventory

import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

object InventoryTestData {
    val now: Instant = Instant.parse("2026-10-06T08:00:00Z")
    val staff = UserId.constructFromPersisted(1)

    /** The lilac of product 1. */
    val lilac = VariantId.constructFromPersisted(101)

    fun delta(value: Int): StockDelta = StockDelta.constructFromPersisted(value)

    fun quantity(value: Int): Quantity = Quantity.constructFromPersisted(value)

    fun order(number: Int): OrderCode = OrderCode.constructFromPersisted("KV-${218_340 + number}")

    /** The lilac's stock after one delivery of [onHand] bags (nothing recorded for 0). */
    fun stocked(onHand: Int): Inventory = Inventory.empty(lilac).also {
        if (onHand > 0) it.adjust(delta(onHand), AdjustmentReason.RESTOCK, null, staff, now).getOrNull()!!
    }

    /** Everything the stock knows: for replay tests. */
    fun Inventory.snapshot(): List<Any?> = listOf(variantId, onHand, reserved, available, reservations)
}
