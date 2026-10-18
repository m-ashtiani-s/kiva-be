package com.kiva.application.dto

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderTimelineKind
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.order.model.policy.OrderActionsPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * An order's card (the contract's OrderSummary), from its read model, without the Persian words: the status's label
 * and tone, the progress steps' labels and the waiting messages are the HTTP layer's.
 *
 * @property shippingMethodName the method's name as the shop's settings call it now.
 * @property paidOn when it was paid; the progress's first step is done from then.
 * @property firstPhotosSentOn when the photos were sent the first time: the progress's second step.
 * @property payable everything paid so far (the placed payable and the balances paid; for an unpaid order, what is to pay).
 * @property balanceDue what it still owes after a change to a dearer color; usually 0.
 * @property messengerChannel where the photo before shipping goes.
 * @property photos what the card says of the photos before shipping (the last batch sent).
 * @property shipment the parcel it left in, once shipped; null before.
 * @property reservation the reservation it is held in, for a RESERVED order; null otherwise.
 */
data class OrderSummaryDto(
    val code: String,
    val status: OrderStatus,
    val placedOn: Instant,
    val paidOn: Instant?,
    val firstPhotosSentOn: Instant?,
    val payable: Money,
    val balanceDue: Money,
    val shippingMethod: ShippingMethodCode,
    val shippingMethodName: String,
    val items: List<ItemPreview>,
    val itemsCount: Int,
    val messengerChannel: MessengerChannel,
    val photos: PhotosSummary,
    val shipment: ShipmentInfo?,
    val actions: OrderActions,
    val reservation: ReservationInfoDto? = null,
) {
    /** One line, as small as the card shows it. [imageUrl] is null until photos are uploaded. */
    data class ItemPreview(val productId: Long, val name: String, val colorKey: String, val imageUrl: String?, val quantity: Int)

    /**
     * The photos before shipping on the card (PreShipmentMediaSummary): where they are, how many the last batch sent had,
     * whether one is a video, and when it was sent. A draft is never counted: the customer doesn't see it.
     */
    data class PhotosSummary(val status: PhotoStatus, val count: Int, val hasVideo: Boolean, val sentOn: Instant?)

    /**
     * The parcel (the contract's Shipment once shipped): its code, the carrier's page that follows it (from the method's
     * `trackingUrlTemplate` now; null when it has none), when it was handed over, and when it was delivered.
     */
    data class ShipmentInfo(val trackingCode: String, val trackingUrl: String?, val shippedOn: Instant, val deliveredOn: Instant?)

    companion object {
        /**
         * [order]'s card as it stands at [now]: its shipping method named as the shop's [settings] call it now (a method
         * without a row any more keeps its code as its name), and the buttons it shows. [media] has the uploads of its
         * photos sent (to tell a video), when there are any.
         */
        fun of(
            order: OrderQm,
            settings: ShopSettings,
            now: Instant,
            reservation: ReservationInfoDto? = null,
            media: Map<MediaId, MediaAsset> = emptyMap(),
        ): OrderSummaryDto = OrderSummaryDto(
            code = order.code.value,
            status = order.status,
            placedOn = order.placedOn,
            paidOn = order.paidOn,
            firstPhotosSentOn = order.timeline.firstOrNull { it.kind == OrderTimelineKind.PHOTOS_SENT }?.at,
            payable = order.totalsView.payable,
            balanceDue = order.balanceDue,
            shippingMethod = order.shippingMethod,
            shippingMethodName = settings.shippingMethods.firstOrNull { it.code == order.shippingMethod }?.name ?: order.shippingMethod.name,
            items = order.lines.map {
                ItemPreview(it.productId.value, it.item.name.value, it.item.color.key.value, it.item.imageUrl, it.quantity.value)
            },
            itemsCount = order.totalsView.itemsCount,
            messengerChannel = order.messenger.channel,
            photos = PhotosSummary(
                status = order.photoStatus,
                count = order.photosSent.size,
                hasVideo = order.photosSent.any { media[it.mediaId]?.type == MediaType.VIDEO },
                sentOn = order.photosSentOn,
            ),
            shipment = order.shipment?.let { parcel ->
                val code = parcel.trackingCode.value
                ShipmentInfo(code, settings.shippingMethods.firstOrNull { it.code == order.shippingMethod }?.trackingUrlOf(code), parcel.shippedOn, order.deliveredOn)
            },
            actions = OrderActionsPolicy.actionsOf(order.actionFactsAt(now)),
            reservation = reservation,
        )
    }
}
