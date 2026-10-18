package com.kiva.application.dto

import com.kiva.common.pagination.Page
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant
import java.time.LocalDate

/**
 * A guest's look at one paid order (`POST /tracking/lookup`): its card ([summary], for the status, progress and parcel)
 * and the photos sent, but no address, prices or names. The HTTP layer shows at most four of [photosSent].
 */
data class GuestTrackingDto(val summary: OrderSummaryDto, val channel: MessengerChannel, val photosSent: List<UploadedMediaDto>) {
    override fun toString(): String = "GuestTrackingDto(code=${summary.code}, status=${summary.status})"
}

/** One tab of the daily list: a day of Tehran, how many parcels left (tracking codes), and whether the shop ships then. */
data class DailyShipmentDayDto(val date: LocalDate, val shipmentCount: Int, val isNonShippingDay: Boolean)

/**
 * One parcel on the public daily list, its recipient already masked (DailyShipmentMask): nothing here is a whole name
 * or number. [id] is the parcel's first order's id.
 */
data class DailyShipmentDto(
    val id: Long,
    val recipientMasked: String,
    val recipientInitial: String,
    val city: String,
    val carrier: ShippingMethodCode,
    val carrierName: String,
    val phoneLast4: String,
    val phoneMasked: String,
    val trackingCode: String,
    val trackingUrl: String?,
    val shippedOn: Instant,
)

/** A day of the daily list: its parcels, a page of them. */
data class DailyShipmentListDto(val date: LocalDate, val isNonShippingDay: Boolean, val shipments: Page<DailyShipmentDto>)

/** A day of «رهگیری‌های من»: the customer's parcels that left then. */
data class MyTrackingDayDto(val date: LocalDate, val shipments: List<Shipment>) {
    data class Shipment(val orderCode: OrderCode, val carrier: ShippingMethodCode, val carrierName: String, val trackingCode: String, val trackingUrl: String?)
}
