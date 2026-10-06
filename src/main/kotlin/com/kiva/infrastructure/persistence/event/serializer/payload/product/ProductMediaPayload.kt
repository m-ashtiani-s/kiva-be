package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.domain.aggregate.product.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import kotlinx.serialization.Serializable

/** One [ProductMedia] as stored. */
@Serializable
data class ProductMediaPayload(
    val type: String,
    val url: String,
    val view: String?,
    val alt: String?,
) {
    constructor(media: ProductMedia) : this(
        type = media.type.name,
        url = media.url,
        view = media.view?.name,
        alt = media.alt,
    )

    fun toDomain(): ProductMedia = ProductMedia.constructFromPersisted(
        type = MediaType.valueOf(type),
        url = url,
        view = view?.let { MediaView.valueOf(it) },
        alt = alt,
    )
}
