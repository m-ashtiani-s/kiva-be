package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.entity.event.ReservationReleased
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.entity.event.StockReserved
import com.kiva.domain.aggregate.inventory.entity.event.StockShipped
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.exposed.tbl.InventoryAdjustmentQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneOffset
import java.util.UUID

/**
 * Keeps the stock columns of the read model in step with the stock's events: `onHand` and `reserved` of the color,
 * and through them `inStock` of its product; and each hand change (StockAdjusted) as a row of the stock's history
 * (inventoryadjustmentqm_tbl, step 1.8). InventoryRepositoryImpl calls [project] for each event right after
 * storing it, in the same transaction.
 *
 * The color must already be in the read model: AdjustInventoryUc checks that before any stock is recorded, so stock
 * for a color no product has is a bug and stops loudly.
 *
 * Runs inside the caller's transaction; it never opens one itself.
 */
object InventoryQmProjector {

    /**
     * Locks the row of [variantId]'s product: what InventoryRepositoryImpl takes **before** it stores the stock's events,
     * so a writer that holds a color's stream always holds its product's row too (roadmap section 11, lock 7 before
     * the streams). Two orders of two colors of one product then wait for each other at the product's row, never in a
     * circle between a row and a stream. Returns the product's id.
     */
    fun lockFor(variantId: VariantId): Long {
        // A color never moves to another product, so finding its product needs no lock.
        val productId = ProductVariantQmTbl.select(ProductVariantQmTbl.productId)
            .where { ProductVariantQmTbl.variantId eq variantId.value }
            .singleOrNull()
            ?.get(ProductVariantQmTbl.productId)
            ?: error("Stock of variant ${variantId.value}, which no product in the read model has")
        // Lock order (ProductQmSummary): the product's row before its color's.
        ProductQmSummary.lock(productId)
        return productId
    }

    fun project(event: InventoryDomainEvent) {
        val variantId = event.variantId.value
        // Locked already by the repository; taking a lock this transaction holds costs nothing.
        val productId = lockFor(event.variantId)

        // The same changes as Inventory.apply, added to what the row has.
        val (onHandChange, reservedChange) = when (event) {
            is StockAdjusted -> event.delta.value to 0
            is StockReserved -> 0 to event.quantity.value
            is ReservationReleased -> 0 to -event.quantity.value
            is StockShipped -> -event.quantity.value to -event.quantity.value
        }
        val updated = ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq variantId }) {
            it[onHand] = onHand + onHandChange
            it[reserved] = reserved + reservedChange
        }
        check(updated == 1) { "Variant $variantId: updated $updated rows of the read model instead of 1" }
        if (event is StockAdjusted) recordAdjustment(event, productId)

        ProductQmSummary.refresh(productId)
    }

    /** A hand change goes in the stock's history, with the stock on hand right after it (read from the row just updated). */
    private fun recordAdjustment(event: StockAdjusted, productId: Long) {
        val color = ProductVariantQmTbl.select(ProductVariantQmTbl.sku, ProductVariantQmTbl.onHand)
            .where { ProductVariantQmTbl.variantId eq event.variantId.value }
            .single()
        InventoryAdjustmentQmTbl.insert {
            it[eventId] = UUID.fromString(event.id.value)
            it[variantId] = event.variantId.value
            it[InventoryAdjustmentQmTbl.productId] = productId
            it[sku] = color[ProductVariantQmTbl.sku]
            it[delta] = event.delta.value
            it[reason] = event.reason.name
            it[note] = event.note?.value
            it[stockAfter] = color[ProductVariantQmTbl.onHand]
            it[occurredOn] = event.occurredOn.atOffset(ZoneOffset.UTC)
            it[occurredByUserId] = event.occurredByUserId.value
        }
    }
}
