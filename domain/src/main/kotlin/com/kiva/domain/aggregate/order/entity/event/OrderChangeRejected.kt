package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import java.time.Instant

/**
 * Staff turned the customer's change request down: the same bag ships. Back to PROCESSING, its photos WAITING for a new
 * batch; the last batch sent stays shown until then.
 *
 * @property note why, for the customer (their timeline, `feedback.resolution.note`); never logged.
 */
data class OrderChangeRejected(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val note: StatusNote,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    override fun toString(): String = "OrderChangeRejected(orderId=${orderId.value}, sequence=${sequence.value})"

    companion object {
        val TYPE = EventType("OrderChangeRejected")
    }
}
