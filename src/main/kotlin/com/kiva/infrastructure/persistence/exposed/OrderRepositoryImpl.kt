package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.infrastructure.persistence.event.EventStore
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer
import com.kiva.infrastructure.persistence.exposed.projector.OrderQmProjector

/**
 * Orders kept as their events in eventstore_tbl (stream ORDER/{orderId}). Runs inside the use case's transaction; it
 * never opens one itself.
 *
 * [save] keeps the read model in step: OrderQmProjector projects each event right after it is stored.
 */
class OrderRepositoryImpl : OrderRepository {

    override fun get(id: OrderId): Order? {
        val events = EventStore.readStream(OrderDomainEvent.AGGREGATE_NAME, EventAggregateId.of(id.value), DomainEventSerializer::deserializeOrder)
        return if (events.isEmpty()) null else Order(events)
    }

    /**
     * Adds the events recorded since the order was read, each followed by its projection. The product rows a payment
     * counts as sold are locked first, before the order's stream (OrderQmProjector.lockFor). If someone else changed
     * the order in the meantime, the save stops with [ConcurrentChangeException] and none of the new events, nor their
     * projections, stay.
     */
    override fun save(order: Order) {
        val events = order.getRecordedEvents()
        if (events.isEmpty()) return
        OrderQmProjector.lockFor(events, order.lines)
        events.forEach {
            EventStore.append(it, DomainEventSerializer.serialize(it))
            OrderQmProjector.project(it)
        }
    }
}
