package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * The contract's MediaAsset, every field always (null when it has none). Addresses are the public base and the stored
 * relative path: `url` the largest photo or the video, `thumbnailUrl` the smallest photo or the smaller cover, `srcSet`
 * every stored size, `posterUrl` a video's largest cover.
 */
@Serializable
data class MediaAssetHttpResponse(
    val id: String,
    val type: String,
    val url: String,
    val thumbnailUrl: String?,
    val srcSet: List<SrcSetItem>,
    val posterUrl: String?,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val mimeType: String,
    val alt: String?,
    val blurhash: String?,
) {
    @Serializable
    data class SrcSetItem(val width: Int, val url: String)

    companion object {
        fun from(media: UploadedMediaDto, urls: MediaUrls) = MediaAssetHttpResponse(
            id = media.id,
            type = media.type.name,
            url = urls.of(media.mainPath),
            thumbnailUrl = urls.of(media.thumbnailPath),
            srcSet = media.variants.map { SrcSetItem(it.width, urls.of(it.path)) },
            posterUrl = media.posterPath?.let(urls::of),
            width = media.width,
            height = media.height,
            durationSec = media.durationSec,
            mimeType = media.mimeType,
            alt = media.alt,
            blurhash = media.blurhash,
        )
    }
}
