package com.kiva.application.dto

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import java.time.Instant

/**
 * One stock change by hand and the stock right after it (the contract's InventoryAdjustment).
 *
 * The contract's `sku` and numeric `id` are not here yet: the SKU of a color comes from the read model (step 5), and
 * an adjustment's own id from the adjustments list that is built from these events.
 *
 * @property onHandAfter bags in the warehouse after the change (the contract's `stockAfter`).
 * @property availableAfter of those, the ones that can still be sold.
 */
data class InventoryAdjustmentDto(
    val variantId: Long,
    val delta: Int,
    val reason: AdjustmentReason,
    val note: String?,
    val onHandAfter: Int,
    val availableAfter: Int,
    val adjustedAt: Instant,
    val adjustedBy: Long,
)
