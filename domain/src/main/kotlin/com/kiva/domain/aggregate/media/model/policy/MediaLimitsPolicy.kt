package com.kiva.domain.aggregate.media.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.media.model.valueobject.ImageFormat
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.media.model.valueobject.VideoContainer

/**
 * What each purpose takes (contract `POST /admin/media`, roadmap section 5.2), and the sizes a photo is stored in.
 *
 * | purpose | photo | video |
 * |---|---|---|
 * | PRODUCT, BLOG | jpeg, png, webp; 15 MB; 40 megapixels; shortest side 320 | mp4 (H.264); 200 MB; 180 s |
 * | PRE_SHIPMENT | the same | mp4 or mov (H.264); 200 MB; 180 s |
 * | CATEGORY, BANNER, TESTIMONIAL | the same | none |
 * | RETURN_EVIDENCE, REVIEW_PHOTO | jpeg, png, webp; 8 MB | mp4 (H.264); 50 MB; 60 s |
 */
object MediaLimitsPolicy {

    const val MAX_MEGAPIXELS = 40
    const val MIN_SHORT_SIDE = 320

    /** The widths a photo is stored in, from the smallest (the thumbnail) to the largest (its `url`). */
    val IMAGE_WIDTHS = listOf(160, 480, 800, 1200, 1600)

    /** The widths of a video's cover frame. */
    val POSTER_WIDTHS = listOf(480, 800)

    private const val MB = 1024L * 1024L

    /**
     * What one purpose takes. [videoContainers] empty means no video at all.
     */
    data class Limits(
        val maxImageBytes: Long,
        val videoContainers: Set<VideoContainer>,
        val maxVideoBytes: Long,
        val maxVideoSeconds: Int,
    ) {
        /** The most any file of this purpose may be: the cap while it is still being read. */
        val maxUploadBytes: Long get() = if (videoContainers.isEmpty()) maxImageBytes else maxOf(maxImageBytes, maxVideoBytes)

        /** The types a client may send, as the 415 answer lists them (`meta.allowed`). */
        val allowedMimeTypes: List<String>
            get() = ImageFormat.entries.map { it.mimeType } + VideoContainer.entries.filter { it in videoContainers }.map { it.mimeType }
    }

    private val STAFF_IMAGE_ONLY = Limits(15 * MB, emptySet(), 0, 0)
    private val STAFF_WITH_VIDEO = Limits(15 * MB, setOf(VideoContainer.MP4), 200 * MB, 180)
    private val PRE_SHIPMENT = Limits(15 * MB, setOf(VideoContainer.MP4, VideoContainer.MOV), 200 * MB, 180)
    private val CUSTOMER = Limits(8 * MB, setOf(VideoContainer.MP4), 50 * MB, 60)

    fun limitsOf(purpose: MediaPurpose): Limits = when (purpose) {
        MediaPurpose.PRODUCT, MediaPurpose.BLOG -> STAFF_WITH_VIDEO
        MediaPurpose.PRE_SHIPMENT -> PRE_SHIPMENT
        MediaPurpose.CATEGORY, MediaPurpose.BANNER, MediaPurpose.TESTIMONIAL -> STAFF_IMAGE_ONLY
        MediaPurpose.RETURN_EVIDENCE, MediaPurpose.REVIEW_PHOTO -> CUSTOMER
    }

    /** The largest upload any purpose takes: the cap while the purpose isn't known yet. */
    val MAX_UPLOAD_BYTES: Long = MediaPurpose.entries.maxOf { limitsOf(it).maxUploadBytes }

    /**
     * Whether a file of [bytes] that turned out to be [probe] may be uploaded for [purpose], and as which type. The type
     * comes first (415), then the size (413), then the dimensions or length (400): a wrong kind of file is wrong at
     * any size.
     */
    fun check(purpose: MediaPurpose, probe: MediaProbe, bytes: Long): Result<MediaType, Failure> {
        val limits = limitsOf(purpose)
        return when (probe) {
            MediaProbe.Unsupported -> Result.failure(Failure.TypeNotAllowed(limits.allowedMimeTypes, hint = null))
            is MediaProbe.Image -> when {
                bytes > limits.maxImageBytes -> Result.failure(Failure.TooLarge(limits.maxImageBytes))
                probe.width.toLong() * probe.height > MAX_MEGAPIXELS * 1_000_000L -> Result.failure(Failure.ImageTooLarge)
                minOf(probe.width, probe.height) < MIN_SHORT_SIDE -> Result.failure(Failure.ImageTooSmall)
                else -> Result.success(MediaType.IMAGE)
            }
            is MediaProbe.Video -> when {
                probe.container !in limits.videoContainers -> Result.failure(Failure.TypeNotAllowed(limits.allowedMimeTypes, hint = null))
                probe.codec == VideoCodec.HEVC -> Result.failure(Failure.TypeNotAllowed(limits.allowedMimeTypes, hint = H264_HINT))
                probe.codec != VideoCodec.H264 -> Result.failure(Failure.TypeNotAllowed(limits.allowedMimeTypes, hint = H264_HINT))
                bytes > limits.maxVideoBytes -> Result.failure(Failure.TooLarge(limits.maxVideoBytes))
                probe.durationSec > limits.maxVideoSeconds -> Result.failure(Failure.VideoTooLong(limits.maxVideoSeconds))
                else -> Result.success(MediaType.VIDEO)
            }
        }
    }

    /**
     * The widths a photo [sourceWidth] pixels wide is stored in: every standard width below it, and its own width up to
     * the largest standard one. Never larger than the photo, and always at least one: a photo 400 wide is 160 and 400.
     */
    fun imageWidthsFor(sourceWidth: Int): List<Int> =
        (IMAGE_WIDTHS.filter { it < sourceWidth } + minOf(sourceWidth, IMAGE_WIDTHS.last())).distinct()

    /** The hint of a refused video codec: a phone set to «Most Compatible» records H.264. */
    const val H264_HINT = "H264"

    // No field: the whole upload is refused (the HTTP layer shows it under `file`).
    sealed class Failure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {

        data class TypeNotAllowed(val allowed: List<String>, val hint: String?) : Failure(
            "UPLOAD_TYPE_NOT_ALLOWED", FailureKind.UNSUPPORTED_MEDIA_TYPE,
            meta = buildMap {
                put("allowed", allowed)
                hint?.let { put("hint", it) }
            },
        )

        data class TooLarge(val maxBytes: Long) : Failure("UPLOAD_TOO_LARGE", FailureKind.PAYLOAD_TOO_LARGE, meta = mapOf("maxBytes" to maxBytes))

        data object ImageTooSmall : Failure("IMAGE_TOO_SMALL", FailureKind.INVALID, "file", mapOf("minSide" to MIN_SHORT_SIDE))

        data object ImageTooLarge : Failure("IMAGE_TOO_LARGE", FailureKind.INVALID, "file", mapOf("maxMegapixels" to MAX_MEGAPIXELS))

        data class VideoTooLong(val maxSeconds: Int) : Failure("VIDEO_TOO_LONG", FailureKind.INVALID, "file", mapOf("maxSeconds" to maxSeconds))
    }
}
