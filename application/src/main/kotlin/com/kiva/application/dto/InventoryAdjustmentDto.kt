package com.kiva.application.dto

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import java.time.Instant

/**
 * One hand change of a color's stock, as the panel's history shows it (the contract's InventoryAdjustment): a row of
 * inventoryadjustmentqm_tbl.
 *
 * @property id the row's id (the contract's `id`).
 * @property sku the code of the color.
 * @property stockAfter bags in the warehouse right after the change.
 * @property createdBy who made it, by name (the system for the seeder's and the jobs' changes).
 */
data class InventoryAdjustmentDto(
    val id: Long,
    val variantId: Long,
    val sku: String,
    val delta: Int,
    val reason: AdjustmentReason,
    val note: String?,
    val stockAfter: Int,
    val createdAt: Instant,
    val createdBy: ActorNameDto,
)
