package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import java.time.Instant

/**
 * Money went back to the customer: the refund [refundId] was completed with the bank's reference. The refund itself is a
 * row of its own; this is the financial fact on the order's history (`totals.refunded`, its timeline). The status
 * doesn't move.
 */
data class OrderRefunded(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val refundId: RefundId,
    val amount: Money,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderRefunded")
    }
}
