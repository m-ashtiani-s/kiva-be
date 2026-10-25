package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import kotlinx.serialization.Serializable

/**
 * An [OrderPhoto]: the upload's id, the line it shows (`orderItemId`), its view and its place.
 *
 * Stored in the OrderPhotosAttached event, and in the same shape in the order read model's `photos` (orderqm_tbl).
 * Never renamed: see EventPayload.
 */
@Serializable
data class OrderPhotoPayload(
    val mediaId: String,
    val lineId: Int?,
    val view: String?,
    val sortOrder: Int,
) {
    constructor(photo: OrderPhoto) : this(photo.mediaId.value, photo.lineId, photo.view?.name, photo.sortOrder)

    fun toDomain(): OrderPhoto = OrderPhoto(MediaId.constructFromPersisted(mediaId), lineId, view?.let(MediaView::valueOf), sortOrder)
}
