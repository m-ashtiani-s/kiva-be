package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import java.time.Instant

/**
 * The operator chose the photos of the packed bag (`PUT …/pre-shipment-media`): [items] replace the draft as a whole.
 * The customer doesn't see a draft; the order's status doesn't move.
 */
data class OrderPhotosAttached(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val items: List<OrderPhoto>,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderPhotosAttached")
    }
}
