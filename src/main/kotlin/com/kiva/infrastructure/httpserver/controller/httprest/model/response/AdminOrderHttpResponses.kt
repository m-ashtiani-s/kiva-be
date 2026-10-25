package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminNoteDto
import com.kiva.application.dto.AdminOrderDto
import com.kiva.application.dto.AdminOrderListDto
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** Contract schema `AdminOrderListResponse`: one page of the queue, and every status's count with the same filters but the status. */
@Serializable
data class AdminOrderListHttpResponse(
    val items: List<AdminOrderListItemHttpResponse>,
    val meta: PageMetaHttpResponse,
    val statusCounts: Map<String, Long>,
) {
    companion object {
        fun from(list: AdminOrderListDto) = AdminOrderListHttpResponse(
            items = list.page.items.map(AdminOrderListItemHttpResponse::from),
            meta = list.page.let { PageMetaHttpResponse(it.page, it.size, it.totalItems, it.totalPages, it.hasNext) },
            statusCounts = list.statusCounts.entries.associate { (status, count) -> status.name to count },
        )
    }
}

/** Contract schema `AdminOrderListItem`. Personal data: [toString] shows the code and status only. */
@Serializable
data class AdminOrderListItemHttpResponse(
    val code: String,
    val placedAt: String,
    val status: String,
    val customerName: String?,
    val customerPhone: String?,
    val city: String,
    val itemsCount: Int,
    val payable: Long,
    val shippingMethod: String,
    val messenger: String,
    val reserved: Boolean,
    val reservationExpiresAt: String?,
    val shipmentGroupCode: String?,
    val mediaCount: Int,
    val trackingCode: String?,
) {
    override fun toString(): String = "AdminOrderListItemHttpResponse(code=$code, status=$status)"

    companion object {
        fun from(item: AdminOrderListDto.Item) = AdminOrderListItemHttpResponse(
            code = item.code,
            placedAt = item.placedOn.toString(),
            status = item.status.name,
            customerName = item.customerName,
            customerPhone = item.customerPhone,
            city = item.city,
            itemsCount = item.itemsCount,
            payable = item.payable.toman,
            shippingMethod = item.shippingMethod.name,
            messenger = item.messenger.name,
            reserved = item.reserved,
            reservationExpiresAt = item.reservationExpiresAt?.toString(),
            shipmentGroupCode = item.shipmentGroupCode,
            mediaCount = item.mediaCount,
            trackingCode = item.trackingCode,
        )
    }
}

/**
 * Contract schema `AdminOrder`: every field of the customer's page (OrderDetail, [OrderDetailHttpResponse]) and the
 * panel's own: the draft of photos only the panel sees, every attempt to pay, the notes.
 */
@Serializable
data class AdminOrderHttpResponse(
    // ---- OrderSummary ----
    val code: String,
    val placedAt: String,
    val status: String,
    val statusLabel: String,
    val statusTone: String,
    val payable: Long,
    val balanceDue: Long,
    val shippingMethod: OrderShippingMethodHttpResponse,
    val itemsPreview: List<OrderItemPreviewHttpResponse>,
    val itemsCount: Int,
    val progress: OrderProgressHttpResponse,
    val reservation: ReservationInfoHttpResponse?,
    val shipment: ShipmentHttpResponse,
    val preShipmentMedia: PreShipmentMediaSummaryHttpResponse,
    val actions: OrderActionsHttpResponse,
    // ---- OrderDetail ----
    val items: List<OrderItemHttpResponse>,
    val address: OrderAddressHttpResponse,
    val totals: OrderTotalsHttpResponse,
    val payment: OrderPaymentInfoHttpResponse?,
    val preShipmentMediaDetail: PreShipmentMediaHttpResponse,
    val timeline: List<OrderEventHttpResponse>,
    val customerNote: String?,
    val gift: OrderGiftHttpResponse?,
    val returns: List<ReturnRequestHttpResponse>,
    val invoiceUrl: String?,
    val refunds: List<RefundHttpResponse>,
    // ---- AdminOrder ----
    val customer: AdminOrderCustomerHttpResponse,
    val shipmentGroup: AdminShipmentGroupHttpResponse?,
    val payments: List<OrderPaymentInfoHttpResponse>,
    val notes: List<AdminNoteHttpResponse>,
    val allowedTransitions: List<String>,
    val draftItems: List<OrderMediaItemHttpResponse>,
) {
    override fun toString(): String = "AdminOrderHttpResponse(code=$code, status=$status)"

    companion object {
        fun from(order: AdminOrderDto, urls: MediaUrls): AdminOrderHttpResponse {
            val page = OrderDetailHttpResponse.from(order.detail, urls)
            return AdminOrderHttpResponse(
                code = page.code,
                placedAt = page.placedAt,
                status = page.status,
                statusLabel = page.statusLabel,
                statusTone = page.statusTone,
                payable = page.payable,
                balanceDue = page.balanceDue,
                shippingMethod = page.shippingMethod,
                itemsPreview = page.itemsPreview,
                itemsCount = page.itemsCount,
                progress = page.progress,
                reservation = page.reservation,
                shipment = page.shipment,
                preShipmentMedia = page.preShipmentMedia,
                actions = page.actions,
                items = page.items,
                address = page.address,
                totals = page.totals,
                payment = page.payment,
                preShipmentMediaDetail = page.preShipmentMediaDetail,
                timeline = page.timeline,
                customerNote = page.customerNote,
                gift = page.gift,
                returns = page.returns,
                invoiceUrl = page.invoiceUrl,
                refunds = page.refunds,
                customer = order.customer.let { AdminOrderCustomerHttpResponse(it.id, it.fullName, it.phone, it.ordersCount) },
                shipmentGroup = order.shipmentGroup?.let { AdminShipmentGroupHttpResponse(it.code, it.orderCodes, it.releaseAt.toString()) },
                payments = order.payments.map(OrderPaymentInfoHttpResponse::from),
                notes = order.notes.map(AdminNoteHttpResponse::from),
                allowedTransitions = order.allowedTransitions.map { it.name }.sorted(),
                draftItems = order.detail.photos.draft.map { OrderMediaItemHttpResponse.from(it, urls) },
            )
        }
    }
}

/** The order's customer (`AdminOrder.customer`). Personal data: [toString] shows the id only. */
@Serializable
data class AdminOrderCustomerHttpResponse(val id: Long, val fullName: String?, val phone: String?, val ordersCount: Int) {
    override fun toString(): String = "AdminOrderCustomerHttpResponse(id=$id)"
}

/** `AdminOrder.shipmentGroup`: the reservation group it ships with, under one tracking code. */
@Serializable
data class AdminShipmentGroupHttpResponse(val code: String, val orderCodes: List<String>, val releaseAt: String)

/** Contract schema `AdminNote`: a panel note, its author by the name their account has now. Personal: [toString] shows the id only. */
@Serializable
data class AdminNoteHttpResponse(val id: Long, val text: String, val author: String, val createdAt: String) {
    override fun toString(): String = "AdminNoteHttpResponse(id=$id)"

    companion object {
        fun from(note: AdminNoteDto) = AdminNoteHttpResponse(note.id, note.text, PanelTexts.actorName(note.author), note.createdOn.toString())
    }
}
