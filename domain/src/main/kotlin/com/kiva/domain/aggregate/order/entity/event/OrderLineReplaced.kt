package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/**
 * A COLOR or MODEL change was carried out: the line [lineId] is now [line], another color copied from the catalog with
 * its price of that moment. The line keeps its number and its quantity (photos and returns name it). The status doesn't
 * move here: OrderChangeApplied follows.
 *
 * @property priceDifference (the new price − the old) × quantity, in toman, signed: worked out once, when it happened,
 *   so reading the events back never prices anything again.
 */
data class OrderLineReplaced(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val lineId: Int,
    val line: OrderLine,
    val priceDifference: Long,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderLineReplaced")
    }
}
