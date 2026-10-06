package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.entity.event.VariantAdded
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class VariantAddedPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val variantId: Long,
    val colorKey: String,
    val sku: String,
    val ownPriceTag: PriceTagPayload?,
    val isDefault: Boolean,
    val sortOrder: Int,
    val media: List<ProductMediaPayload>,
) : EventPayload<VariantAdded> {

    constructor(event: VariantAdded) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        variantId = event.variantId.value,
        colorKey = event.colorKey.value,
        sku = event.sku.value,
        ownPriceTag = event.ownPriceTag?.let { PriceTagPayload(it) },
        isDefault = event.isDefault,
        sortOrder = event.sortOrder,
        media = event.media.map { ProductMediaPayload(it) },
    )

    override fun toDomainEvent(): VariantAdded = VariantAdded(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        variantId = VariantId.constructFromPersisted(variantId),
        colorKey = ColorKey.constructFromPersisted(colorKey),
        sku = Sku.constructFromPersisted(sku),
        ownPriceTag = ownPriceTag?.toDomain(),
        isDefault = isDefault,
        sortOrder = sortOrder,
        media = media.map { it.toDomain() },
        id = EventId.constructFromPersisted(id),
    )
}
