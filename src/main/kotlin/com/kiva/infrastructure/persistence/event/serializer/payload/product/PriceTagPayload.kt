package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import kotlinx.serialization.Serializable

/** A [PriceTag] as stored: amounts in toman. */
@Serializable
data class PriceTagPayload(
    val price: Long,
    val compareAtPrice: Long?,
) {
    constructor(priceTag: PriceTag) : this(
        price = priceTag.price.toman,
        compareAtPrice = priceTag.compareAtPrice?.toman,
    )

    fun toDomain(): PriceTag = PriceTag.constructFromPersisted(
        price = Money.constructFromPersisted(price),
        compareAtPrice = compareAtPrice?.let { Money.constructFromPersisted(it) },
    )
}
