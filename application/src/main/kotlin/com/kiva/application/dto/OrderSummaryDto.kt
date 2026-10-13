package com.kiva.application.dto

import com.kiva.application.repository.OrderQm
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.policy.OrderActionsPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
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
 * @property messengerChannel where the photo before shipping goes.
 * @property reservation the reservation it is held in, for a RESERVED order; null otherwise.
 */
data class OrderSummaryDto(
    val code: String,
    val status: OrderStatus,
    val placedOn: Instant,
    val paidOn: Instant?,
    val payable: Money,
    val shippingMethod: ShippingMethodCode,
    val shippingMethodName: String,
    val items: List<ItemPreview>,
    val itemsCount: Int,
    val messengerChannel: MessengerChannel,
    val actions: OrderActions,
    val reservation: ReservationInfoDto? = null,
) {
    /** One line, as small as the card shows it. [imageUrl] is null until photos are uploaded. */
    data class ItemPreview(val productId: Long, val name: String, val colorKey: String, val imageUrl: String?, val quantity: Int)

    companion object {
        /**
         * [order]'s card as it stands at [now]: its shipping method named as the shop's [settings] call it now (a method
         * without a row any more keeps its code as its name), and the buttons it shows.
         */
        fun of(order: OrderQm, settings: ShopSettings, now: Instant, reservation: ReservationInfoDto? = null): OrderSummaryDto = from(
            order,
            settings.shippingMethods.firstOrNull { it.code == order.shippingMethod }?.name ?: order.shippingMethod.name,
            OrderActionsPolicy.actionsOf(order.status, order.holdUntil, now),
        ).copy(reservation = reservation)

        fun from(order: OrderQm, shippingMethodName: String, actions: OrderActions) = OrderSummaryDto(
            code = order.code.value,
            status = order.status,
            placedOn = order.placedOn,
            paidOn = order.paidOn,
            payable = order.totals.payable,
            shippingMethod = order.shippingMethod,
            shippingMethodName = shippingMethodName,
            items = order.lines.map {
                ItemPreview(it.productId.value, it.item.name.value, it.item.color.key.value, it.item.imageUrl, it.quantity.value)
            },
            itemsCount = order.totals.itemsCount,
            messengerChannel = order.messenger.channel,
            actions = actions,
        )
    }
}
