package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.repository.ProductCodeTakenException
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.infrastructure.persistence.event.EventStore
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer
import com.kiva.infrastructure.persistence.exposed.projector.ProductQmProjector
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException

/**
 * Products kept as their events in eventstore_tbl. Runs inside the use case's transaction; it never opens one itself.
 *
 * Only [get] and [save]: the shop's lists and pages read the read model (productqm_tbl), never the event store.
 * [save] keeps that read model in step: ProductQmProjector projects each event right after it is stored.
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
     * Adds the events recorded since the product was read, each followed by its projection into the read model. If
     * someone else saved a change to it in the meantime, the save stops with [ConcurrentChangeException]; if another
     * product took its slug or a SKU, with [ProductCodeTakenException]. Either way none of the new events, nor their
     * projections, stay.
     */
    override fun save(product: Product) {
        product.getRecordedEvents().forEach {
            EventStore.append(it, DomainEventSerializer.serialize(it))
            try {
                ProductQmProjector.project(it)
            } catch (e: ExposedSQLException) {
                throw codeTakenOrNull(product, e) ?: e
            }
        }
    }

    /** The read model's unique keys on slug and SKU are the last word on them; any other refusal is a bug. */
    private fun codeTakenOrNull(product: Product, e: ExposedSQLException): ProductCodeTakenException? {
        val refusal = (e.cause as? PSQLException)?.serverErrorMessage ?: return null
        val constraint: String? = refusal.constraint
        // The database names the code: «Key (sku)=(KV-1001) already exists.»
        val detail: String? = refusal.detail
        return when (constraint) {
            ProductQmTbl.SLUG_KEY -> ProductCodeTakenException.SlugTaken(product.slug, e)
            ProductQmTbl.SKU_KEY, ProductVariantQmTbl.SKU_KEY -> ProductCodeTakenException.SkuTaken(
                detail?.let { TAKEN_VALUE.find(it) }?.groupValues?.get(1)?.let(Sku::constructFromPersisted),
                e,
            )
            else -> null
        }
    }

    private companion object {
        val TAKEN_VALUE = Regex("""\)=\((.+)\) already exists""")
    }
}
