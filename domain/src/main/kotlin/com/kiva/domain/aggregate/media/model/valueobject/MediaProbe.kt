package com.kiva.domain.aggregate.media.model.valueobject

/**
 * What a file turned out to be, read from its content (never its name or the client's header) by the media processor,
 * before anything is decoded: enough for MediaLimitsPolicy to accept or refuse it.
 */
sealed interface MediaProbe {

    /** A photo the shop takes: [width] and [height] as it will show (after its EXIF rotation). */
    data class Image(val format: ImageFormat, val width: Int, val height: Int) : MediaProbe

    data class Video(val container: VideoContainer, val codec: VideoCodec, val width: Int, val height: Int, val durationSec: Int) : MediaProbe

    /** Anything else: SVG, GIF, HEIC, a program named `.jpg`, a broken file. */
    data object Unsupported : MediaProbe
}

enum class ImageFormat(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
}

enum class VideoContainer(val mimeType: String) {
    MP4("video/mp4"),

    /** QuickTime: a phone's own video, taken only for the photos before shipping. */
    MOV("video/quicktime"),
}

enum class VideoCodec {
    H264,

    /** HEVC, an iPhone's default: refused, with a hint to set the camera to «Most Compatible». */
    HEVC,
    OTHER,
}
