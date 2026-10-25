package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The optional body of `POST /products/{productId}/stock-alerts`: a `phone` an old client still sends is ignored (decision 1). */
@Serializable
data class CreateStockAlertHttpRequest(val variantId: Long? = null, val channel: String? = null)
