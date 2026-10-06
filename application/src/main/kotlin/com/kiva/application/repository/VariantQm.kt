package com.kiva.application.repository

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * One color of a product as the read model has it. Only what the use cases need so far; the shop's pages add theirs.
 *
 * @property isActive false when the color was taken out of the shop. It still exists: past orders and stock point to it.
 */
data class VariantQm(
    val variantId: VariantId,
    val productId: ProductId,
    val colorKey: ColorKey,
    val sku: Sku,
    val isActive: Boolean,
)
