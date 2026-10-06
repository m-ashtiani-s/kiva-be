package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.DomainEvent
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.event.valueobject.EventVersion
import com.kiva.domain.aggregate.product.model.valueobject.ProductId

/**
 * Everything that can happen to a product. Each event carries only what changed; the stream of one product,
 * replayed in order, rebuilds it.
 *
 * The `type` of each event is stored with it and read back to pick the class, so a type in use is never renamed.
 */
sealed class ProductDomainEvent : DomainEvent {
    abstract val productId: ProductId

    override val aggregateName: EventAggregateName get() = AGGREGATE_NAME
    override val aggregateId: EventAggregateId get() = EventAggregateId.of(productId.value)
    override val version: EventVersion get() = EventVersion.FIRST

    companion object {
        val AGGREGATE_NAME = EventAggregateName("PRODUCT")
    }
}
