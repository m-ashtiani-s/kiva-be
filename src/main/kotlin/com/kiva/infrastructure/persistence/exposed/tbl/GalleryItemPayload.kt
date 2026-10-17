package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.application.dto.GalleryItemDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import kotlinx.serialization.Serializable

/**
 * One item of a color's gallery as productvariantqm_tbl's `media` keeps it: the product's reference (file, side, alt)
 * with a copy of everything the pages need from the file's media row (sizes, paths, blurhash), so a product page
 * never reads media_tbl. A media row is never changed once written, so the copy is never stale.
 *
 * Before step 1.5 this column held the event's shape (with a `url`); no row ever had an item (checked on the dev
 * database), so every row read as an empty list either way.
 *
 * @property alt the gallery item's own alt, or the file's when the panel gave none.
 */
@Serializable
data class GalleryItemPayload(
    val mediaId: String,
    val type: String,
    val view: String,
    val alt: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val blurhash: String?,
    val variants: List<SizePayload>,
    val mainPath: String,
    val thumbnailPath: String,
    val posterPath: String?,
) {
    @Serializable
    data class SizePayload(val width: Int, val path: String)

    constructor(media: ProductMedia, asset: MediaAsset) : this(
        mediaId = media.mediaId.value,
        type = asset.type.name,
        view = media.view.name,
        alt = media.alt?.value ?: asset.alt?.value,
        mimeType = asset.mimeType,
        width = asset.width,
        height = asset.height,
        durationSec = asset.durationSec,
        blurhash = asset.blurhash,
        variants = asset.variants.map { SizePayload(it.width, it.path) },
        mainPath = asset.mainPath,
        thumbnailPath = asset.thumbnailPath,
        posterPath = asset.posterPath,
    )

    val isVideo: Boolean get() = type == MediaType.VIDEO.name

    fun toDto() = GalleryItemDto(
        media = UploadedMediaDto(
            id = mediaId,
            type = MediaType.valueOf(type),
            mimeType = mimeType,
            mainPath = mainPath,
            thumbnailPath = thumbnailPath,
            variants = variants.map { UploadedMediaDto.Variant(it.width, it.path) },
            posterPath = posterPath,
            width = width,
            height = height,
            durationSec = durationSec,
            alt = alt,
            blurhash = blurhash,
        ),
        view = MediaView.valueOf(view),
    )
}
