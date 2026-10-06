package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProductCreatedPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val slug: String,
    val sku: String,
    val details: ProductDetailsPayload,
    val priceTag: PriceTagPayload,
) : EventPayload<ProductCreated> {

    constructor(event: ProductCreated) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        slug = event.slug.value,
        sku = event.sku.value,
        details = ProductDetailsPayload(event.details),
        priceTag = PriceTagPayload(event.priceTag),
    )

    override fun toDomainEvent(): ProductCreated = ProductCreated(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        slug = Slug.constructFromPersisted(slug),
        sku = Sku.constructFromPersisted(sku),
        details = details.toDomain(),
        priceTag = priceTag.toDomain(),
        id = EventId.constructFromPersisted(id),
    )
}
