package com.kiva.application.dto

import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaType

/**
 * A stored file as the contract's MediaAsset needs it, with paths relative to the media root: the HTTP layer puts the
 * public base in front (kiva.media.publicBaseUrl), so moving the files later is a change of configuration only.
 *
 * @property mainPath a photo's largest size, or the video.
 * @property thumbnailPath a photo's smallest size, or a video's smaller cover.
 * @property variants every stored size (a video's covers), smallest first.
 */
data class UploadedMediaDto(
    val id: String,
    val type: MediaType,
    val mimeType: String,
    val mainPath: String,
    val thumbnailPath: String,
    val variants: List<Variant>,
    val posterPath: String?,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val alt: String?,
    val blurhash: String?,
) {
    data class Variant(val width: Int, val path: String)

    companion object {
        fun from(asset: MediaAsset) = UploadedMediaDto(
            id = asset.id.value,
            type = asset.type,
            mimeType = asset.mimeType,
            mainPath = asset.mainPath,
            thumbnailPath = asset.thumbnailPath,
            variants = asset.variants.map { Variant(it.width, it.path) },
            posterPath = asset.posterPath,
            width = asset.width,
            height = asset.height,
            durationSec = asset.durationSec,
            alt = asset.alt?.value,
            blurhash = asset.blurhash,
        )
    }
}
