package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import java.time.Instant

/**
 * The order was cancelled before it shipped: CANCELLED, for good. Its stock goes back and its money is to be refunded
 * (OrderCancellation does both with it). When is [occurredOn].
 *
 * @property reason the customer's; null when staff cancelled.
 * @property note shown on the customer's timeline.
 */
data class OrderCancelled(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val by: CancelledBy,
    val reason: CancelReason?,
    val note: StatusNote?,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    override fun toString(): String = "OrderCancelled(orderId=${orderId.value}, sequence=${sequence.value}, by=$by, reason=$reason)"

    companion object {
        val TYPE = EventType("OrderCancelled")
    }
}
