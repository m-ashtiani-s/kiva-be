package com.kiva.application.dto

import com.kiva.application.repository.ProductBriefQm
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import java.time.Instant

/**
 * One «موجود شد خبرم کن» subscription as its customer sees it (contract StockAlert, without its Persian message).
 *
 * @property color the color waited for; null for «any color».
 * @property phoneMasked the account's phone now, masked (`0912***6789`): where the SMS goes.
 */
data class StockAlertDto(
    val id: Long,
    val product: ProductBriefQm,
    val variantId: Long?,
    val color: Color?,
    val phoneMasked: String,
    val status: StockAlertStatus,
    val createdAt: Instant,
    val notifiedAt: Instant?,
) {
    override fun toString(): String = "StockAlertDto(id=$id, status=$status)"
}
