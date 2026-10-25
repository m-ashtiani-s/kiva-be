package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * The order left with the courier (`PUT …/shipment`): SHIPPED. [shippedOn] is when it was handed over, which the operator
 * may give as earlier than when it was recorded ([occurredOn]).
 *
 * @property showInDailyList listed, masked, on «کدهای رهگیری روزانه».
 * @property reservationGroupId the group it shipped with, under one tracking code; null for an order on its own.
 */
data class OrderShipped(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val carrier: ShippingMethodCode,
    val trackingCode: TrackingCode,
    val shippedOn: Instant,
    val showInDailyList: Boolean,
    val reservationGroupId: ReservationGroupId?,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderShipped")
    }
}
