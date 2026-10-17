package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.OrderSummaryDto
import com.kiva.application.dto.PaymentInitDto
import com.kiva.application.dto.ReservationInfoDto
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** Contract schema `PlaceOrderResponse`: the order, and where to pay it; [payment] is `null` for an order paid at once. */
@Serializable
data class PlaceOrderHttpResponse(val order: OrderSummaryHttpResponse, val payment: PaymentInitHttpResponse?)

/**
 * Contract schema `OrderSummary`: an order's card. [reservation] only for an order held with «رزرو ۴ روزه». In this phase
 * the shipment only waits, the photos before shipping are still to come, and [balanceDue] is 0 (no change requests yet).
 */
@Serializable
data class OrderSummaryHttpResponse(
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
) {
    companion object {
        fun from(order: OrderSummaryDto, urls: MediaUrls): OrderSummaryHttpResponse {
            val current = OrderTexts.currentStepOf(order.status)
            return OrderSummaryHttpResponse(
                code = order.code,
                placedAt = order.placedOn.toString(),
                status = order.status.name,
                statusLabel = OrderTexts.statusLabel(order.status),
                statusTone = OrderTexts.statusTone(order.status),
                payable = order.payable.toman,
                balanceDue = 0,
                shippingMethod = OrderShippingMethodHttpResponse(order.shippingMethod.name, order.shippingMethodName),
                itemsPreview = order.items.map { OrderItemPreviewHttpResponse(it.productId, it.name, it.colorKey, it.imageUrl?.let(urls::of), it.quantity) },
                itemsCount = order.itemsCount,
                progress = OrderProgressHttpResponse(
                    currentStepIndex = current ?: 0,
                    steps = OrderTexts.progressSteps.mapIndexed { index, (key, label, icon) ->
                        val state = when {
                            current == null || index > current -> "UPCOMING"
                            index == current -> "CURRENT"
                            else -> "DONE"
                        }
                        // Only the first step's moment is known in this phase: the payment.
                        OrderProgressStepHttpResponse(key, label, icon, state, if (index == 0 && current != null) order.paidOn?.toString() else null)
                    },
                ),
                reservation = order.reservation?.let(ReservationInfoHttpResponse::from),
                shipment = ShipmentHttpResponse(
                    carrier = order.shippingMethod.name,
                    carrierName = order.shippingMethodName,
                    trackingCode = null,
                    trackingUrl = null,
                    shippedAt = null,
                    deliveredAt = null,
                    waitingMessage = OrderTexts.shipmentWaiting(order.shippingMethodName),
                ),
                preShipmentMedia = PreShipmentMediaSummaryHttpResponse(
                    status = "WAITING",
                    channel = order.messengerChannel.name,
                    channelName = OrderTexts.messengerName(order.messengerChannel),
                    count = 0,
                    hasVideo = false,
                    sentAt = null,
                ),
                actions = OrderActionsHttpResponse.from(order.actions),
            )
        }
    }
}

/** The order's shipping method: its code and its name in the shop's settings. */
@Serializable
data class OrderShippingMethodHttpResponse(val code: String, val name: String)

/** Contract schema `OrderItemPreview`. [imageUrl] is `null` until photos are uploaded. */
@Serializable
data class OrderItemPreviewHttpResponse(val productId: Long, val name: String, val colorKey: String, val imageUrl: String?, val quantity: Int)

/** Contract schema `OrderProgress`: always the four steps. */
@Serializable
data class OrderProgressHttpResponse(val currentStepIndex: Int, val steps: List<OrderProgressStepHttpResponse>)

/** Contract schema `OrderProgressStep`. */
@Serializable
data class OrderProgressStepHttpResponse(val key: String, val label: String, val icon: String, val state: String, val at: String?)

/** Contract schema `ReservationInfo`: a held «رزرو ۴ روزه», its countdown and its orders. */
@Serializable
data class ReservationInfoHttpResponse(
    val active: Boolean,
    val orderCode: String,
    val startedAt: String,
    val expiresAt: String,
    val remainingSeconds: Long,
    val groupOrderCodes: List<String>,
    val message: String,
) {
    companion object {
        fun from(info: ReservationInfoDto) = ReservationInfoHttpResponse(
            active = info.active,
            orderCode = info.orderCode,
            startedAt = info.startedAt.toString(),
            expiresAt = info.expiresAt.toString(),
            remainingSeconds = info.remainingSeconds,
            groupOrderCodes = info.groupOrderCodes,
            message = ReservationTexts.holdsUntil(info.expiresAt),
        )
    }
}

/** Contract schema `Shipment`. */
@Serializable
data class ShipmentHttpResponse(
    val carrier: String,
    val carrierName: String,
    val trackingCode: String?,
    val trackingUrl: String?,
    val shippedAt: String?,
    val deliveredAt: String?,
    val waitingMessage: String?,
)

/** Contract schema `PreShipmentMediaSummary`. */
@Serializable
data class PreShipmentMediaSummaryHttpResponse(
    val status: String,
    val channel: String,
    val channelName: String,
    val count: Int,
    val hasVideo: Boolean,
    val sentAt: String?,
)

/** The `actions` of OrderSummary: which buttons the card shows. */
@Serializable
data class OrderActionsHttpResponse(
    val canCancel: Boolean,
    val canRequestChange: Boolean,
    val canApproveMedia: Boolean,
    val canReturn: Boolean,
    val canPay: Boolean,
    val canAddToReservation: Boolean,
    val canReview: Boolean,
) {
    companion object {
        fun from(actions: OrderActions) = OrderActionsHttpResponse(
            actions.canCancel, actions.canRequestChange, actions.canApproveMedia, actions.canReturn, actions.canPay, actions.canAddToReservation,
            actions.canReview,
        )
    }
}

/** Contract schema `PaymentInit`. */
@Serializable
data class PaymentInitHttpResponse(
    val paymentId: String,
    val gateway: String,
    val amount: Long,
    val redirect: PaymentRedirectHttpResponse,
    val expiresAt: String,
) {
    companion object {
        fun from(payment: PaymentInitDto) = PaymentInitHttpResponse(
            paymentId = payment.paymentId,
            gateway = payment.gateway.name,
            amount = payment.amount.toman,
            redirect = PaymentRedirectHttpResponse(payment.redirect.url, payment.redirect.method.name, payment.redirect.fields),
            expiresAt = payment.expiresAt.toString(),
        )
    }
}

/** Where the browser goes: a GET has only [url]; a POST is a form to send with [fields]. */
@Serializable
data class PaymentRedirectHttpResponse(val url: String, val method: String, val fields: Map<String, String>)
