package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import kotlinx.serialization.Serializable

/** [ProductSpecs] as stored. Enum values are kept by name (`ZIPPER`), so they are never renamed once in use. */
@Serializable
data class ProductSpecsPayload(
    val dimensions: DimensionsPayload?,
    val weightGrams: Int?,
    val closure: String?,
    val strap: String?,
    val pockets: String?,
    val occasions: List<String>,
) {
    constructor(specs: ProductSpecs) : this(
        dimensions = specs.dimensions?.let { DimensionsPayload(it) },
        weightGrams = specs.weightGrams,
        closure = specs.closure?.name,
        strap = specs.strap,
        pockets = specs.pockets,
        occasions = specs.occasions.map { it.name },
    )

    fun toDomain(): ProductSpecs = ProductSpecs.constructFromPersisted(
        dimensions = dimensions?.toDomain(),
        weightGrams = weightGrams,
        closure = closure?.let { ClosureType.valueOf(it) },
        strap = strap,
        pockets = pockets,
        occasions = occasions.map { Occasion.valueOf(it) }.toSet(),
    )
}
