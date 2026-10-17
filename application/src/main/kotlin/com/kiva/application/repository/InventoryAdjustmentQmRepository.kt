package com.kiva.application.repository

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * The stock's history of hand changes (inventoryadjustmentqm_tbl, step 1.8), written by the projector from each
 * StockAdjusted. Runs inside the caller's transaction.
 */
interface InventoryAdjustmentQmRepository {

    /** The row of one StockAdjusted event, or null when it has none (it is written in the event's own transaction). */
    fun findByEventId(eventId: EventId): InventoryAdjustmentQm?

    /** The newest first (then the later row first), every color or only [variantId]'s. */
    fun find(variantId: VariantId?, pageable: Pageable): Page<InventoryAdjustmentQm>
}

/**
 * One hand change of a color's stock.
 *
 * @property id the contract's InventoryAdjustment.id.
 * @property stockAfter bags on hand right after this change.
 */
data class InventoryAdjustmentQm(
    val id: Long,
    val variantId: VariantId,
    val sku: Sku,
    val delta: Int,
    val reason: AdjustmentReason,
    val note: String?,
    val stockAfter: Int,
    val occurredOn: Instant,
    val occurredBy: UserId,
)
