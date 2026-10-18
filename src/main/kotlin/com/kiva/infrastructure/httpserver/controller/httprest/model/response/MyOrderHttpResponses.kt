package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.dto.OrderListDto
import com.kiva.application.dto.PreShipmentMediaDto
import com.kiva.application.usecase.order.usecase.command.ReorderUc
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Contract schema `OrderListResponse`: one page of order cards, and the counts of the tabs. */
@Serializable
data class OrderListHttpResponse(
    val items: List<OrderSummaryHttpResponse>,
    val meta: PageMetaHttpResponse,
    val counts: OrderCountsHttpResponse,
) {
    companion object {
        fun from(list: OrderListDto, urls: MediaUrls) = OrderListHttpResponse(
            items = list.page.items.map { OrderSummaryHttpResponse.from(it, urls) },
            meta = list.page.let { PageMetaHttpResponse(it.page, it.size, it.totalItems, it.totalPages, it.hasNext) },
            counts = list.counts.let { OrderCountsHttpResponse(it.all, it.current, it.delivered, it.cancelled) },
        )
    }
}

@Serializable
data class OrderCountsHttpResponse(val all: Long, val current: Long, val delivered: Long, val cancelled: Long)

/**
 * Contract schema `OrderDetail`: every field of the card (OrderSummary) and the page's own. In this phase the photos
 * before shipping are still to come ([preShipmentMediaDetail] WAITING, no items), there are no returns and no invoice,
 * and the gift is never wrapped.
 */
@Serializable
data class OrderDetailHttpResponse(
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
    // ReturnRequest objects, from the returns phase on.
    val returns: List<JsonObject>,
    val invoiceUrl: String?,
    val refunds: List<RefundHttpResponse>,
) {
    companion object {
        fun from(order: OrderDetailDto, urls: MediaUrls): OrderDetailHttpResponse {
            val card = OrderSummaryHttpResponse.from(order.summary, urls)
            return OrderDetailHttpResponse(
                code = card.code,
                placedAt = card.placedAt,
                status = card.status,
                statusLabel = card.statusLabel,
                statusTone = card.statusTone,
                payable = card.payable,
                balanceDue = card.balanceDue,
                shippingMethod = card.shippingMethod,
                itemsPreview = card.itemsPreview,
                itemsCount = card.itemsCount,
                progress = card.progress,
                reservation = card.reservation,
                shipment = card.shipment,
                preShipmentMedia = card.preShipmentMedia,
                actions = card.actions,
                items = order.items.map { OrderItemHttpResponse.from(it, urls) },
                address = OrderAddressHttpResponse.from(order.address),
                totals = OrderTotalsHttpResponse.from(order.totals, order.refunded),
                payment = order.payment?.let(OrderPaymentInfoHttpResponse::from),
                preShipmentMediaDetail = PreShipmentMediaHttpResponse.from(order.photos, order.messenger, urls),
                timeline = order.timeline.map { entry ->
                    OrderEventHttpResponse(
                        status = entry.status.name,
                        label = OrderTexts.timelineLabel(entry.kind, entry.status, order.summary.shippingMethodName, entry.amount),
                        at = entry.at.toString(),
                        note = entry.failureReason?.let { PaymentTexts.failureReason(it, order.summary.status) } ?: entry.note,
                    )
                },
                customerNote = order.customerNote,
                gift = order.gift?.let { OrderGiftHttpResponse(wrap = false, message = it.message) },
                returns = emptyList(),
                invoiceUrl = null,
                refunds = order.refunds.map(RefundHttpResponse::from),
            )
        }
    }
}

/** Contract schema `OrderItem`: a line as bought. No photos yet; reviews and returns come in later phases. */
@Serializable
data class OrderItemHttpResponse(
    val id: Int,
    val productId: Long,
    val variantId: Long,
    val sku: String,
    val name: String,
    val slug: String,
    val color: ColorHttpResponse,
    val image: String?,
    val quantity: Int,
    val unitPrice: Long,
    val unitCompareAtPrice: Long?,
    val lineTotal: Long,
    val reviewed: Boolean,
    val returnableUntil: String?,
) {
    companion object {
        fun from(item: OrderDetailDto.Item, urls: MediaUrls) = OrderItemHttpResponse(
            id = item.lineId,
            productId = item.productId,
            variantId = item.variantId,
            sku = item.sku,
            name = item.name,
            slug = item.slug,
            color = item.color.let { ColorHttpResponse(it.key, it.name, it.hex, it.isLight, it.sortOrder) },
            image = item.imageUrl?.let(urls::of),
            quantity = item.quantity,
            unitPrice = item.unitPrice.toman,
            unitCompareAtPrice = item.unitCompareAtPrice?.toman,
            lineTotal = item.lineTotal.toman,
            reviewed = false,
            returnableUntil = null,
        )
    }
}

/** Contract schema `AddressSnapshot`. Personal data: [toString] shows none of it. */
@Serializable
data class OrderAddressHttpResponse(
    val provinceName: String,
    val cityName: String,
    val addressLine: String,
    val plaque: String?,
    val unit: String?,
    val postalCode: String,
    val recipientName: String,
    val recipientPhone: String,
    val fullText: String,
) {
    override fun toString(): String = "OrderAddressHttpResponse(****)"

    companion object {
        fun from(address: OrderDetailDto.Address) = OrderAddressHttpResponse(
            provinceName = address.provinceName,
            cityName = address.cityName,
            addressLine = address.addressLine,
            plaque = address.plaque,
            unit = address.unit,
            postalCode = address.postalCode,
            recipientName = address.recipientName,
            recipientPhone = address.recipientPhone,
            fullText = AddressTexts.fullText(address.provinceName, address.cityName, address.addressLine, address.plaque, address.unit),
        )
    }
}

/** Contract schema `OrderTotals`. [refunded] is what the completed refunds gave back; no change requests yet (balanceDue 0). */
@Serializable
data class OrderTotalsHttpResponse(
    val itemsCompareAtTotal: Long,
    val productDiscount: Long,
    val subtotal: Long,
    val discountCode: String?,
    val codeDiscount: Long,
    val shippingCost: Long,
    val shippingFreeReason: String?,
    val giftWrapCost: Long,
    val payable: Long,
    val refunded: Long,
    val balanceDue: Long,
    val currency: String,
) {
    companion object {
        fun from(totals: OrderTotals, refunded: Money) = OrderTotalsHttpResponse(
            itemsCompareAtTotal = totals.itemsCompareAtTotal.toman,
            productDiscount = totals.productDiscount.toman,
            subtotal = totals.subtotal.toman,
            discountCode = totals.discountCode?.value,
            codeDiscount = totals.codeDiscount.toman,
            shippingCost = totals.shippingCost.toman,
            shippingFreeReason = totals.shippingFreeReason?.name,
            giftWrapCost = 0,
            payable = totals.payable.toman,
            refunded = refunded.toman,
            balanceDue = 0,
            currency = PriceInfoHttpResponse.TOMAN,
        )
    }
}

/** Contract schema `OrderPaymentInfo`: the order's latest attempt to pay. */
@Serializable
data class OrderPaymentInfoHttpResponse(
    val gateway: String,
    val gatewayName: String,
    val status: String,
    val referenceId: String?,
    val paidAt: String?,
    val cardMask: String?,
) {
    companion object {
        fun from(payment: OrderDetailDto.Payment) = OrderPaymentInfoHttpResponse(
            gateway = payment.gateway.name,
            gatewayName = payment.gatewayName,
            status = payment.outcome.name,
            referenceId = payment.refId,
            paidAt = payment.paidOn?.toString(),
            cardMask = payment.cardMask,
        )
    }
}

/**
 * Contract schema `PreShipmentMedia`: [items] is the last batch sent, and [waitingMessage] stands in while none was. The
 * panel's answer to `PUT …/pre-shipment-media` is the same shape with the draft in [items] ([fromDraft]). Personal data:
 * [toString] shows the channel and status only.
 */
@Serializable
data class PreShipmentMediaHttpResponse(
    val status: String,
    val channel: String,
    val channelName: String,
    val phone: String,
    val username: String?,
    val note: String?,
    val sentAt: String?,
    val waitingMessage: String?,
    val items: List<OrderMediaItemHttpResponse>,
    val feedback: PreShipmentFeedbackHttpResponse?,
    val feedbackDeadline: String?,
) {
    override fun toString(): String = "PreShipmentMediaHttpResponse(status=$status, channel=$channel)"

    companion object {
        /** What the customer sees: the batch sent, never the draft. */
        fun from(photos: PreShipmentMediaDto, messenger: OrderDetailDto.Messenger, urls: MediaUrls) = of(photos, messenger, photos.sent, urls)

        /** The panel's view of the draft it just set. */
        fun fromDraft(photos: PreShipmentMediaDto, messenger: OrderDetailDto.Messenger, urls: MediaUrls) = of(photos, messenger, photos.draft, urls)

        private fun of(photos: PreShipmentMediaDto, messenger: OrderDetailDto.Messenger, items: List<PreShipmentMediaDto.Photo>, urls: MediaUrls): PreShipmentMediaHttpResponse {
            val messengerName = OrderTexts.messengerName(messenger.channel)
            return PreShipmentMediaHttpResponse(
                status = photos.status.name,
                channel = messenger.channel.name,
                channelName = messengerName,
                phone = messenger.phone,
                username = messenger.username,
                note = messenger.note,
                sentAt = photos.sentOn?.toString(),
                waitingMessage = OrderTexts.photosWaiting(messengerName).takeIf { photos.sent.isEmpty() },
                items = items.map { OrderMediaItemHttpResponse.from(it, urls) },
                feedback = photos.feedback?.let { PreShipmentFeedbackHttpResponse(it.decision.name, null, null, null, null, it.at.toString(), null) },
                feedbackDeadline = photos.feedbackDeadline?.toString(),
            )
        }
    }
}

/** Contract schema `OrderMediaItem`: a MediaAsset, and the order line it shows, its view and when it was taken. */
@Serializable
data class OrderMediaItemHttpResponse(
    val id: String,
    val type: String,
    val url: String,
    val thumbnailUrl: String?,
    val srcSet: List<MediaAssetHttpResponse.SrcSetItem>,
    val posterUrl: String?,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val mimeType: String,
    val alt: String?,
    val blurhash: String?,
    val orderItemId: Int?,
    val view: String?,
    val capturedAt: String,
) {
    companion object {
        fun from(photo: PreShipmentMediaDto.Photo, urls: MediaUrls): OrderMediaItemHttpResponse {
            val asset = MediaAssetHttpResponse.from(photo.media, urls)
            return OrderMediaItemHttpResponse(
                id = asset.id, type = asset.type, url = asset.url, thumbnailUrl = asset.thumbnailUrl, srcSet = asset.srcSet, posterUrl = asset.posterUrl,
                width = asset.width, height = asset.height, durationSec = asset.durationSec, mimeType = asset.mimeType, alt = asset.alt,
                blurhash = asset.blurhash, orderItemId = photo.lineId, view = photo.view?.name, capturedAt = photo.capturedAt.toString(),
            )
        }
    }
}

/**
 * `PreShipmentMedia.feedback`: the customer's last answer. In this phase only APPROVE; the change request's fields and its
 * resolution are phase 3's and null until then.
 */
@Serializable
data class PreShipmentFeedbackHttpResponse(
    val decision: String,
    val changeType: String?,
    val orderItemId: Int?,
    val desiredVariantId: Long?,
    val note: String?,
    val at: String,
    val resolution: JsonObject?,
)

/** Contract schema `OrderEvent`: one line of the timeline. [note] says why an attempt to pay failed, or is staff's or the customer's note. */
@Serializable
data class OrderEventHttpResponse(val status: String, val label: String, val at: String, val note: String?)

/** The order's `gift`; never wrapped while the shop offers no wrap. */
@Serializable
data class OrderGiftHttpResponse(val wrap: Boolean, val message: String?)

/** Contract schema `WishlistAddToCartResponse`, the answer of «خرید دوباره»: the cart now, what went in, what didn't. */
@Serializable
data class ReorderHttpResponse(
    val cart: CartHttpResponse,
    val addedCount: Int,
    val skipped: List<ReorderSkippedHttpResponse>,
    val message: String,
) {
    companion object {
        fun from(cart: CartHttpResponse, reorder: ReorderUc.Reorder) = ReorderHttpResponse(
            cart = cart,
            addedCount = reorder.addedQuantity,
            skipped = reorder.skipped.map { ReorderSkippedHttpResponse(it.productId, it.name, it.reason.name) },
            message = OrderTexts.reordered(reorder.addedQuantity),
        )
    }
}

@Serializable
data class ReorderSkippedHttpResponse(val productId: Long, val name: String, val reason: String)
