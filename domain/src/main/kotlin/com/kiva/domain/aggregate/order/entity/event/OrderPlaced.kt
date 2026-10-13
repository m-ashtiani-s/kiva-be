package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * A customer placed an order: everything it is, copied at that moment, so nothing that changes later (a price, a
 * product's name, the saved address) ever changes it. Its stock is held until [holdUntil]; it waits for the money.
 * When it was placed is [occurredOn].
 *
 * @property holdUntil the end of the time to pay and of the stock hold (`PaymentInit.expiresAt`); never moved.
 * @property reserveRequested «رزرو ۴ روزه» was asked for, for an order of its own (never with [reservationGroupId]).
 * @property reservationGroupId the customer's held reservation it joins, shipping free with it (round 2); decided when
 *   placed, and kept even if the group's time ends before the order is paid. Null in events stored before step 19.
 */
data class OrderPlaced(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val code: OrderCode,
    val userId: UserId,
    val lines: List<OrderLine>,
    val address: AddressSnapshot,
    val messenger: PreShipmentMessenger,
    val shippingMethod: ShippingMethodCode,
    val totals: OrderTotals,
    val reserveRequested: Boolean,
    val holdUntil: Instant,
    val customerNote: CustomerNote?,
    val gift: GiftNote?,
    val reservationGroupId: ReservationGroupId? = null,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    // The address, the messenger and the notes are personal data; the lines and totals are long.
    override fun toString(): String = "OrderPlaced(orderId=${orderId.value}, code=${code.value}, sequence=${sequence.value}, lines=${lines.size})"

    companion object {
        val TYPE = EventType("OrderPlaced")
    }
}
