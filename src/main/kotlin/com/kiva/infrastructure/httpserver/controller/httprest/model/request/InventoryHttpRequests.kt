package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `POST /admin/inventory/adjustments` (contract InventoryAdjustmentRequest). Every field nullable. */
@Serializable
data class InventoryAdjustmentHttpRequest(
    val variantId: Long? = null,
    val delta: Int? = null,
    val reason: String? = null,
    val note: String? = null,
)
