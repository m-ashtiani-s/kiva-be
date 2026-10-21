package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import java.time.Instant

/**
 * The customer asked to send lines of the delivered order back: RETURN_REQUESTED, the return [returnId] open. The return
 * itself (its lines, reason and evidence) is a row of its own; this is the fact on the order's history, so its status
 * comes from its own events, never from another table.
 */
data class OrderReturnRequested(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val returnId: ReturnId,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderReturnRequested")
    }
}
