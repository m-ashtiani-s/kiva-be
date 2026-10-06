package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.infrastructure.persistence.event.EventStore
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer

/**
 * Products kept as their events in eventstore_tbl. Runs inside the use case's transaction; it never opens one itself.
 *
 * Only [get] and [save]: the shop's lists and pages read the read model (step 5), never the event store.
 */
class ProductRepositoryImpl : ProductRepository {

    override fun get(id: ProductId): Product? {
        val events = EventStore.readStream(
            ProductDomainEvent.AGGREGATE_NAME,
            EventAggregateId.of(id.value),
            DomainEventSerializer::deserializeProduct,
        )
        return if (events.isEmpty()) null else Product(events)
    }

    /**
     * Adds the events recorded since the product was read. If someone else saved a change to it in the meantime, the
     * save stops with [ConcurrentChangeException] and none of the new events stay.
     */
    override fun save(product: Product) {
        product.getRecordedEvents().forEach { EventStore.append(it, DomainEventSerializer.serialize(it)) }
    }
}
