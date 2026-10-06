package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.event.VariantMediaReplaced
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class VariantMediaReplacedPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val variantId: Long,
    val media: List<ProductMediaPayload>,
) : EventPayload<VariantMediaReplaced> {

    constructor(event: VariantMediaReplaced) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        variantId = event.variantId.value,
        media = event.media.map { ProductMediaPayload(it) },
    )

    override fun toDomainEvent(): VariantMediaReplaced = VariantMediaReplaced(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        variantId = VariantId.constructFromPersisted(variantId),
        media = media.map { it.toDomain() },
        id = EventId.constructFromPersisted(id),
    )
}
