package com.kiva.application.dto

import com.kiva.application.repository.ProductBriefQm
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import java.time.Instant

/**
 * One row of the panel's «موجود شد خبرم کن» queue (contract AdminStockAlertGroup): how many wait for one color of a
 * product, or for any of its colors. Nobody's phone: the panel only counts.
 *
 * @property variantId null, with [color] and [sku]: «any color».
 * @property availableQuantity what can be sold now: of that color, or of all the product's active colors for «any color».
 */
data class AdminStockAlertGroupDto(
    val product: ProductBriefQm,
    val variantId: Long?,
    val color: Color?,
    val sku: String?,
    val waitingCount: Int,
    val oldestAt: Instant,
    val stockStatus: StockStatus,
    val availableQuantity: Int,
)
