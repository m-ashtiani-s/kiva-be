package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaType

/**
 * One photo or video of one color of a product: an uploaded file ([mediaId], never changed once made), what kind it is,
 * from which side it shows the bag and its alt text. A color holds its media as an ordered list: the first one is the
 * card photo, the order is the gallery order (ProductGalleryPolicy).
 *
 * Only the reference is kept here: the file's sizes, addresses and blurhash belong to the media row, and the read model
 * copies them in when it is written (a media row is never changed, so that copy is never stale).
 */
@ConsistentCopyVisibility
data class ProductMedia private constructor(
    val mediaId: MediaId,
    val type: MediaType,
    val view: MediaView,
    val alt: MediaAlt?,
) {
    companion object {
        /**
         * Every part is already a valid value; [type] is the media row's own (a photo or a video), never the client's
         * word, so the use case makes it once it has read the row.
         */
        fun of(mediaId: MediaId, type: MediaType, view: MediaView, alt: MediaAlt?): ProductMedia = ProductMedia(mediaId, type, view, alt)

        /** For values read back from storage. */
        fun constructFromPersisted(mediaId: MediaId, type: MediaType, view: MediaView, alt: MediaAlt?): ProductMedia =
            ProductMedia(mediaId, type, view, alt)
    }
}
