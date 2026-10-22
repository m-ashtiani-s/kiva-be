package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.StockAlertDto
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** The Persian words of «موجود شد خبرم کن» (roadmap section 15.2, decision 7). */
object StockAlertTexts {
    fun message(status: StockAlertStatus): String = when (status) {
        StockAlertStatus.ACTIVE -> "هر وقت موجود شد، بهت پیامک می‌دیم"
        StockAlertStatus.NOTIFIED -> "موجود شد و بهت خبر دادیم"
        StockAlertStatus.CANCELLED -> "اشتراک لغو شد"
    }
}

/** Contract schema `StockAlert`: every field always there. The phone only masked; [toString] shows none of it. */
@Serializable
data class StockAlertHttpResponse(
    val id: Long,
    val product: ProductRefHttpResponse,
    val variantId: Long?,
    val color: ColorHttpResponse?,
    val channel: String,
    val phoneMasked: String,
    val status: String,
    val createdAt: String,
    val notifiedAt: String?,
    val message: String,
) {
    override fun toString(): String = "StockAlertHttpResponse(id=$id, status=$status)"

    companion object {
        fun from(alert: StockAlertDto, urls: MediaUrls) = StockAlertHttpResponse(
            id = alert.id,
            product = ReviewHttpResponse.refOf(alert.product, urls).copy(colorKey = alert.color?.key?.value),
            variantId = alert.variantId,
            color = alert.color?.let { ColorHttpResponse(it.key.value, it.name.value, it.hex.value, it.isLight, it.sortOrder) },
            channel = "SMS",
            phoneMasked = alert.phoneMasked,
            status = alert.status.name,
            createdAt = alert.createdAt.toString(),
            notifiedAt = alert.notifiedAt?.toString(),
            message = StockAlertTexts.message(alert.status),
        )
    }
}
