package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.domain.aggregate.product.model.valueobject.Dimensions
import kotlinx.serialization.Serializable

/** [Dimensions] as stored, in centimeters. */
@Serializable
data class DimensionsPayload(
    val lengthCm: Double,
    val heightCm: Double,
    val widthCm: Double,
) {
    constructor(dimensions: Dimensions) : this(
        lengthCm = dimensions.lengthCm,
        heightCm = dimensions.heightCm,
        widthCm = dimensions.widthCm,
    )

    fun toDomain(): Dimensions = Dimensions.constructFromPersisted(lengthCm, heightCm, widthCm)
}
