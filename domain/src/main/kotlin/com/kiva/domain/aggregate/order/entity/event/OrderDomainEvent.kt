package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.DomainEvent
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.event.valueobject.EventVersion
import com.kiva.domain.aggregate.order.model.valueobject.OrderId

/**
 * Everything that can happen to an order. The stream's id is the order's numeric id (`ORDER/42`); its timeline
 * (`OrderDetail.timeline`) is made of these events.
 *
 * This phase has the purchase: placed, a payment failed, paid, expired, and let go of by its reservation. The later
 * phases (the photo before shipping, shipping, cancelling, returns) add their events here.
 *
 * The `type` of each event is stored with it and read back to pick the class, so a type in use is never renamed.
 */
sealed class OrderDomainEvent : DomainEvent {
    abstract val orderId: OrderId

    override val aggregateName: EventAggregateName get() = AGGREGATE_NAME
    override val aggregateId: EventAggregateId get() = EventAggregateId.of(orderId.value)
    override val version: EventVersion get() = EventVersion.FIRST

    companion object {
        val AGGREGATE_NAME = EventAggregateName("ORDER")
    }
}
