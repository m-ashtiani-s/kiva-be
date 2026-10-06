package com.kiva.application.dto

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import java.time.Instant

/**
 * One stock change by hand and the stock right after it (the contract's InventoryAdjustment).
 *
 * The contract's numeric `id` is not here yet: an adjustment's own id comes with the adjustments list that is built
 * from these events.
 *
 * @property sku the code of the color, from the read model.
 * @property onHandAfter bags in the warehouse after the change (the contract's `stockAfter`).
 * @property availableAfter of those, the ones that can still be sold.
 */
data class InventoryAdjustmentDto(
    val variantId: Long,
    val sku: String,
    val delta: Int,
    val reason: AdjustmentReason,
    val note: String?,
    val onHandAfter: Int,
    val availableAfter: Int,
    val adjustedAt: Instant,
    val adjustedBy: Long,
)
