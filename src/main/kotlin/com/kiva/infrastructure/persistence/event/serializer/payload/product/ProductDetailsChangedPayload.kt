package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.event.ProductDetailsChanged
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProductDetailsChangedPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val details: ProductDetailsPayload,
) : EventPayload<ProductDetailsChanged> {

    constructor(event: ProductDetailsChanged) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        details = ProductDetailsPayload(event.details),
    )

    override fun toDomainEvent(): ProductDetailsChanged = ProductDetailsChanged(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        details = details.toDomain(),
        id = EventId.constructFromPersisted(id),
    )
}
