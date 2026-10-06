package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.event.ProductArchived
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProductArchivedPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
) : EventPayload<ProductArchived> {

    constructor(event: ProductArchived) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
    )

    override fun toDomainEvent(): ProductArchived = ProductArchived(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        id = EventId.constructFromPersisted(id),
    )
}
