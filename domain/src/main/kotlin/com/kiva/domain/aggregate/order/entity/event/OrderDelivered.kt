package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import java.time.Instant

/**
 * The customer has the order: DELIVERED, recorded by staff (`POST …/status`). When is [occurredOn], the moment it was
 * recorded: there is no link to the carriers to know better.
 *
 * @property returnableUntil the end of its time to return, fixed now with the shop's return window of now, so a later
 *   change of the settings never moves it (as `OrderPaid.heldForReservation`); null when none of its lines is returnable.
 * @property note staff's note, shown on the customer's timeline.
 */
data class OrderDelivered(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val returnableUntil: Instant?,
    val note: StatusNote? = null,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    override fun toString(): String = "OrderDelivered(orderId=${orderId.value}, sequence=${sequence.value}, returnableUntil=$returnableUntil)"

    companion object {
        val TYPE = EventType("OrderDelivered")
    }
}
