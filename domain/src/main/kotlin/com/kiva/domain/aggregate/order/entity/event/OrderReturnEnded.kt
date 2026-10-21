package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import java.time.Instant

/**
 * The open return [returnId] ended with [outcome]: turned down or closed, the order is DELIVERED again; its money given
 * back, the order is REFUNDED (beside the OrderRefunded of that refund). Closed or refunded, it is never returned again.
 */
data class OrderReturnEnded(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val returnId: ReturnId,
    val outcome: ReturnOutcome,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderReturnEnded")
    }
}
