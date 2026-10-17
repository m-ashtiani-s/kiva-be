package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import kotlinx.serialization.Serializable

/**
 * One [ProductMedia] as stored in a product event: the uploaded file's id, its kind, its side and its alt.
 *
 * Before uploads existed a gallery item was an address ([url]); that field stays, as payload fields never go, and is
 * written null from now on. No stored event ever had a gallery item (checked on the dev database before step 1.5:
 * every stored `media` was empty), so one without a [mediaId] is broken data and stops.
 */
@Serializable
data class ProductMediaPayload(
    val type: String,
    val url: String? = null,
    val view: String?,
    val alt: String?,
    val mediaId: String? = null,
) {
    constructor(media: ProductMedia) : this(
        type = media.type.name,
        url = null,
        view = media.view.name,
        alt = media.alt?.value,
        mediaId = media.mediaId.value,
    )

    fun toDomain(): ProductMedia = ProductMedia.constructFromPersisted(
        mediaId = MediaId.constructFromPersisted(mediaId ?: error("A stored gallery item without a mediaId (url=$url)")),
        type = MediaType.valueOf(type),
        view = MediaView.valueOf(view ?: error("A stored gallery item without a view (mediaId=$mediaId)")),
        alt = alt?.let(MediaAlt::constructFromPersisted),
    )
}
