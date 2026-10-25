package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.InventoryAdjustmentDto
import kotlinx.serialization.Serializable

/** Contract schema `InventoryAdjustment`. [createdBy] is a name to show («سیستم» for the system). */
@Serializable
data class InventoryAdjustmentHttpResponse(
    val id: Long,
    val variantId: Long,
    val sku: String,
    val delta: Int,
    val reason: String,
    val note: String?,
    val stockAfter: Int,
    val createdAt: String,
    val createdBy: String,
) {
    companion object {
        fun from(adjustment: InventoryAdjustmentDto) = InventoryAdjustmentHttpResponse(
            id = adjustment.id,
            variantId = adjustment.variantId,
            sku = adjustment.sku,
            delta = adjustment.delta,
            reason = adjustment.reason.name,
            note = adjustment.note,
            stockAfter = adjustment.stockAfter,
            createdAt = adjustment.createdAt.toString(),
            createdBy = PanelTexts.actorName(adjustment.createdBy),
        )
    }
}
