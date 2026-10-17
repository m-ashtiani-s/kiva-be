package com.kiva.application.dto

import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView

/**
 * One photo or video of a color's gallery as the pages show it: the uploaded file with every size (the read model's
 * copy of its media row) and the side of the bag it shows. Its place in the list is its place in the gallery; the
 * first one is the card photo.
 *
 * @property media its alt is the gallery item's own, or the file's when the panel gave none.
 */
data class GalleryItemDto(val media: UploadedMediaDto, val view: MediaView) {
    val type: MediaType get() = media.type
}
