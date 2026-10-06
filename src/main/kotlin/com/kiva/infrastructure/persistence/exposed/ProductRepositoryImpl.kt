package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.postgresql.util.PSQLException
import java.time.ZoneOffset
import java.util.UUID

/**
 * Products kept as their events in eventstore_tbl. Runs inside the use case's transaction; it never opens one itself.
 *
 * Only [get] and [save]: the shop's lists and pages read the read model (step 5), never the event store.
 */
class ProductRepositoryImpl : ProductRepository {

    override fun get(id: ProductId): Product? {
        val events = streamOf(EventAggregateId.of(id.value))
        return if (events.isEmpty()) null else Product(events)
    }

    /**
     * Adds the events recorded since the product was read. If someone else saved a change to it in the meantime, the
     * first new event takes a number already used, the database refuses it and the save stops with
     * [ConcurrentChangeException]; the transaction then rolls back, so none of the new events stay.
     */
    override fun save(product: Product) {
        product.getRecordedEvents().forEach { append(it) }
    }

    // By name and id together: product 7 and, later, the stock of variant 7 are different streams.
    private fun streamOf(aggregateId: EventAggregateId): List<ProductDomainEvent> =
        EventStoreTbl.selectAll()
            .where {
                (EventStoreTbl.aggregateName eq ProductDomainEvent.AGGREGATE_NAME.value) and
                    (EventStoreTbl.aggregateId eq aggregateId.value)
            }
            .orderBy(EventStoreTbl.sequence to SortOrder.ASC)
            .map {
                DomainEventSerializer.deserializeProduct(
                    payload = it[EventStoreTbl.payload],
                    payloadVersion = it[EventStoreTbl.payloadVersion],
                    eventType = it[EventStoreTbl.eventType],
                )
            }

    private fun append(event: ProductDomainEvent) {
        try {
            EventStoreTbl.insert {
                it[eventId] = UUID.fromString(event.id.value)
                it[aggregateName] = event.aggregateName.value
                it[aggregateId] = event.aggregateId.value
                it[sequence] = event.sequence.value
                it[eventType] = event.type.value
                it[payloadVersion] = event.version.value
                it[payload] = DomainEventSerializer.serialize(event)
                it[occurredOn] = event.occurredOn.atOffset(ZoneOffset.UTC)
                it[occurredByUserId] = event.occurredByUserId.value
            }
        } catch (e: ExposedSQLException) {
            // Only a repeated number in the stream means "changed by someone else"; any other refusal is a bug.
            if ((e.cause as? PSQLException)?.serverErrorMessage?.constraint == EventStoreTbl.STREAM_SEQUENCE_KEY) {
                throw ConcurrentChangeException(event.aggregateName, event.aggregateId, e)
            }
            throw e
        }
    }
}
