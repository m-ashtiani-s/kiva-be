package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.event.ProductPublishScheduled
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [publishAt] is an ISO-8601 instant, like every moment stored. */
@Serializable
data class ProductPublishScheduledPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val publishAt: String,
) : EventPayload<ProductPublishScheduled> {

    constructor(event: ProductPublishScheduled) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        publishAt = event.publishAt.toString(),
    )

    override fun toDomainEvent(): ProductPublishScheduled = ProductPublishScheduled(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        publishAt = Instant.parse(publishAt),
        id = EventId.constructFromPersisted(id),
    )
}
