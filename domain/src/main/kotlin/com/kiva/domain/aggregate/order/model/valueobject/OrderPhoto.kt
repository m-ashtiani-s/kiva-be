package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView

/**
 * One photo or video of the packed bag, as the order keeps it: which upload, of which of its lines, from which side,
 * and where it stands among the others. The file itself (its addresses, size, blurhash) is the media's, read when shown.
 *
 * @property lineId the order line it shows (`orderItemId`); null when it shows the whole order.
 * @property sortOrder its place among the photos, smallest first.
 */
data class OrderPhoto(val mediaId: MediaId, val lineId: Int?, val view: MediaView?, val sortOrder: Int) {
    init {
        require(lineId == null || lineId >= 1) { "Invalid order line id $lineId of photo ${mediaId.value}" }
        require(sortOrder >= 0) { "Invalid sort order $sortOrder of photo ${mediaId.value}" }
    }
}
