package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.PriceDto
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.infrastructure.common.http.toPersianNumber

/**
 * The Persian words, tones and icons of a product's codes, as the prototype shows them. The domain gives only codes
 * (BadgeCode, StockStatus); the words live here, like ErrorMessages for errors.
 */
object ProductTexts {

    /** One badge of a card: its label (with the discount or the number left when it has one), tone and icon. */
    fun badge(code: BadgeCode, price: PriceDto, stock: StockInfo): ProductBadgeHttpResponse = when (code) {
        BadgeCode.NEW -> ProductBadgeHttpResponse(code.name, "جدید", tone = "DEFAULT")
        BadgeCode.SALE -> ProductBadgeHttpResponse(code.name, "${price.discountPercent.toLong().toPersianNumber()}٪ تخفیف", tone = "SALE")
        BadgeCode.HAS_VIDEO -> ProductBadgeHttpResponse(code.name, "ویدیو دارد", tone = "GLASS", icon = "play")
        BadgeCode.FREE_SHIPPING -> ProductBadgeHttpResponse(code.name, "ارسال رایگان", tone = "DEFAULT")
        BadgeCode.SOLD_OUT -> ProductBadgeHttpResponse(code.name, "تمام شد", tone = "DARK")
        BadgeCode.LOW_STOCK -> ProductBadgeHttpResponse(code.name, "فقط ${shown(stock)} عدد", tone = "DANGER")
    }

    fun stockLabel(stock: StockInfo): String = when (stock.status) {
        StockStatus.IN_STOCK -> "موجود در انبار"
        StockStatus.LOW_STOCK -> "فقط ${shown(stock)} عدد در انبار باقی مونده"
        StockStatus.OUT_OF_STOCK -> "ناموجود"
    }

    // StockPolicy gives the number exactly when the status is LOW_STOCK.
    private fun shown(stock: StockInfo): String =
        (stock.shownQuantity ?: error("LOW_STOCK without a number: $stock")).toLong().toPersianNumber()
}
