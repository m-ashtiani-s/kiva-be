package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.DailyShipmentDayDto
import com.kiva.application.dto.DailyShipmentDto
import com.kiva.application.dto.DailyShipmentListDto
import com.kiva.application.dto.GuestTrackingDto
import com.kiva.application.dto.MyTrackingDayDto
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.common.text.JalaliDates
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** The Persian words of the tracking pages. */
object TrackingTexts {
    /** A tab of the daily list: «امروز»، «دیروز», or the weekday. */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "امروز"
        today.minusDays(1) -> "دیروز"
        else -> JalaliDates.weekday(date)
    }

    const val NON_SHIPPING_DAY = "جمعه‌ها ارسال نداریم"

    const val LOGIN_HINT = "برای دیدن جزئیات کامل، آدرس و همه عکس‌ها وارد حساب کاربری شو."

    /** How many photos a guest sees. */
    const val PREVIEW_COUNT = 4
}

/** Contract schema `GuestTrackingResult`: no address, prices or names. */
@Serializable
data class GuestTrackingHttpResponse(
    val code: String,
    val placedAt: String,
    val shippingMethod: OrderShippingMethodHttpResponse,
    val status: String,
    val statusLabel: String,
    val statusTone: String,
    val progress: OrderProgressHttpResponse,
    val shipment: ShipmentHttpResponse,
    val preShipmentMedia: GuestMediaHttpResponse,
    val loginHint: String,
) {
    @Serializable
    data class GuestMediaHttpResponse(val channelName: String, val preview: List<MediaAssetHttpResponse>, val totalCount: Int)

    companion object {
        fun from(tracking: GuestTrackingDto, urls: MediaUrls): GuestTrackingHttpResponse {
            val card = OrderSummaryHttpResponse.from(tracking.summary, urls)
            return GuestTrackingHttpResponse(
                code = card.code,
                placedAt = card.placedAt,
                shippingMethod = card.shippingMethod,
                status = card.status,
                statusLabel = card.statusLabel,
                statusTone = card.statusTone,
                progress = card.progress,
                shipment = card.shipment,
                preShipmentMedia = GuestMediaHttpResponse(
                    channelName = OrderTexts.messengerName(tracking.channel),
                    preview = tracking.photosSent.take(TrackingTexts.PREVIEW_COUNT).map { MediaAssetHttpResponse.from(it, urls) },
                    totalCount = tracking.photosSent.size,
                ),
                loginHint = TrackingTexts.LOGIN_HINT,
            )
        }
    }
}

/** Contract schema `DailyShipmentDay`. */
@Serializable
data class DailyShipmentDayHttpResponse(val date: String, val label: String, val shipmentCount: Int, val isNonShippingDay: Boolean) {
    companion object {
        fun from(day: DailyShipmentDayDto, today: LocalDate) =
            DailyShipmentDayHttpResponse(day.date.toString(), TrackingTexts.dayLabel(day.date, today), day.shipmentCount, day.isNonShippingDay)
    }
}

/** Contract schema `DailyShipmentEntry`: the recipient masked already (the application's DailyShipmentMask). */
@Serializable
data class DailyShipmentEntryHttpResponse(
    val id: Long,
    val recipientMasked: String,
    val recipientInitial: String,
    val city: String,
    val carrier: String,
    val carrierName: String,
    val phoneLast4: String,
    val phoneMasked: String,
    val trackingCode: String,
    val trackingUrl: String?,
    val shippedAt: String,
) {
    companion object {
        fun from(entry: DailyShipmentDto) = DailyShipmentEntryHttpResponse(
            entry.id, entry.recipientMasked, entry.recipientInitial, entry.city, entry.carrier.name, entry.carrierName, entry.phoneLast4, entry.phoneMasked,
            entry.trackingCode, entry.trackingUrl, entry.shippedOn.toString(),
        )
    }
}

/** Contract schema `DailyShipmentListResponse`. */
@Serializable
data class DailyShipmentListHttpResponse(
    val date: String,
    val label: String,
    val isNonShippingDay: Boolean,
    val emptyMessage: String?,
    val items: List<DailyShipmentEntryHttpResponse>,
    val meta: PageMetaHttpResponse,
) {
    companion object {
        fun from(list: DailyShipmentListDto): DailyShipmentListHttpResponse {
            val page = list.shipments
            return DailyShipmentListHttpResponse(
                date = list.date.toString(),
                label = JalaliDates.weekdayDayMonthAndYear(list.date),
                isNonShippingDay = list.isNonShippingDay,
                emptyMessage = TrackingTexts.NON_SHIPPING_DAY.takeIf { list.isNonShippingDay },
                items = page.items.map(DailyShipmentEntryHttpResponse::from),
                meta = PageMetaHttpResponse(page.page, page.size, page.totalItems, page.totalPages, page.hasNext),
            )
        }
    }
}

/** Contract schema `TrackingDayGroup`. */
@Serializable
data class TrackingDayGroupHttpResponse(val date: String, val label: String, val shipments: List<Shipment>) {
    @Serializable
    data class Shipment(val orderCode: String, val carrier: String, val carrierName: String, val trackingCode: String, val trackingUrl: String?)

    companion object {
        fun from(day: MyTrackingDayDto) = TrackingDayGroupHttpResponse(
            date = day.date.toString(),
            label = JalaliDates.weekdayDayAndMonth(day.date),
            shipments = day.shipments.map { Shipment(it.orderCode.value, it.carrier.name, it.carrierName, it.trackingCode, it.trackingUrl) },
        )
    }
}
