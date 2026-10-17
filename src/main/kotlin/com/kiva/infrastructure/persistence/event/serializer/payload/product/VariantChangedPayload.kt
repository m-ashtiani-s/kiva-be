package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.event.VariantChanged
import com.kiva.domain.aggregate.product.model.valueobject.Barcode
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class VariantChangedPayload(
    val id: String,
    val productId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val variantId: Long,
    val ownPriceTag: PriceTagPayload?,
    val sortOrder: Int,
    val isDefault: Boolean,
    val barcode: String? = null,
) : EventPayload<VariantChanged> {

    constructor(event: VariantChanged) : this(
        id = event.id.value,
        productId = event.productId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        variantId = event.variantId.value,
        ownPriceTag = event.ownPriceTag?.let { PriceTagPayload(it) },
        sortOrder = event.sortOrder,
        isDefault = event.isDefault,
        barcode = event.barcode?.value,
    )

    override fun toDomainEvent(): VariantChanged = VariantChanged(
        productId = ProductId.constructFromPersisted(productId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        variantId = VariantId.constructFromPersisted(variantId),
        ownPriceTag = ownPriceTag?.toDomain(),
        sortOrder = sortOrder,
        isDefault = isDefault,
        barcode = barcode?.let(Barcode::constructFromPersisted),
        id = EventId.constructFromPersisted(id),
    )
}
