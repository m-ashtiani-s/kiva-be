package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductTags
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * [ProductDetails] as stored. [newUntil] is an ISO-8601 instant (`2026-10-20T00:00:00Z`).
 *
 * [reservable] came after the first events were stored, so those don't have it. Its default is what they meant: until
 * then every product could be reserved. A field added with such a default keeps payload version 1; a change that
 * makes old payloads mean something else needs a new version and a reader for the old one. [tags] and [seo] came in
 * step 1.5 the same way: none and none.
 */
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
    val reservable: Boolean = true,
    val sortPriority: Int,
    val tags: List<String> = emptyList(),
    val seo: SeoPayload? = null,
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
        reservable = details.reservable,
        sortPriority = details.sortPriority,
        tags = details.tags.items,
        seo = details.seo.takeIf { it != SeoMeta.NONE }?.let { SeoPayload(it.title, it.description, it.noIndex) },
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
        reservable = reservable,
        sortPriority = sortPriority,
        tags = ProductTags.constructFromPersisted(tags),
        seo = seo?.let { SeoMeta.constructFromPersisted(it.title, it.description, it.noIndex) } ?: SeoMeta.NONE,
    )
}

@Serializable
data class SeoPayload(val title: String?, val description: String?, val noIndex: Boolean)
