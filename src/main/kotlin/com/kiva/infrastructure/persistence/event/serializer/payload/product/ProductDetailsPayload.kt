package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import kotlinx.serialization.Serializable
import java.time.Instant

/** [ProductDetails] as stored. [newUntil] is an ISO-8601 instant (`2026-10-20T00:00:00Z`). */
@Serializable
data class ProductDetailsPayload(
    val name: String,
    val categoryId: Long,
    val materialId: Long,
    val materialDescription: String?,
    val bagType: String,
    val shortDescription: String?,
    val description: String?,
    val highlights: List<String>,
    val specs: ProductSpecsPayload,
    val newUntil: String?,
    val maxPerOrder: Int,
    val returnable: Boolean,
    val sortPriority: Int,
) {
    constructor(details: ProductDetails) : this(
        name = details.name.value,
        categoryId = details.categoryId.value,
        materialId = details.materialId.value,
        materialDescription = details.materialDescription?.value,
        bagType = details.bagType.name,
        shortDescription = details.shortDescription?.value,
        description = details.description?.value,
        highlights = details.highlights.items,
        specs = ProductSpecsPayload(details.specs),
        newUntil = details.newUntil?.toString(),
        maxPerOrder = details.maxPerOrder.value,
        returnable = details.returnable,
        sortPriority = details.sortPriority,
    )

    fun toDomain(): ProductDetails = ProductDetails(
        name = ProductName.constructFromPersisted(name),
        categoryId = CategoryId.constructFromPersisted(categoryId),
        materialId = MaterialId.constructFromPersisted(materialId),
        materialDescription = materialDescription?.let { MaterialDescription.constructFromPersisted(it) },
        bagType = BagType.valueOf(bagType),
        shortDescription = shortDescription?.let { ShortDescription.constructFromPersisted(it) },
        description = description?.let { ProductDescription.constructFromPersisted(it) },
        highlights = Highlights.constructFromPersisted(highlights),
        specs = specs.toDomain(),
        newUntil = newUntil?.let { Instant.parse(it) },
        maxPerOrder = MaxPerOrder.constructFromPersisted(maxPerOrder),
        returnable = returnable,
        sortPriority = sortPriority,
    )
}
