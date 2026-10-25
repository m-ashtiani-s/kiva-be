package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/**
 * A CANCEL_ITEM change was carried out on an order of several lines: the line [lineId] is gone (its number is never
 * used again). The status doesn't move here: OrderChangeApplied follows.
 *
 * @property paidShare what the customer paid for it: its total minus its share of the discount code
 *   (ChangeRequestPolicy.paidShareOf), worked out once, when it happened. Shipping never comes back.
 */
data class OrderLineRemoved(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val lineId: Int,
    val paidShare: Money,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderLineRemoved")
    }
}
