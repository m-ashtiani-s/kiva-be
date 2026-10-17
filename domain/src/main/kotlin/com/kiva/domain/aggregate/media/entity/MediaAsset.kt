package com.kiva.domain.aggregate.media.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import java.time.Instant

/**
 * One uploaded file (a photo or a video), processed and stored: the contract's MediaAsset. Never changed after it is
 * made, so whatever points at it (a color's gallery, an order's snapshot) always shows the same thing; the only thing
 * that moves is [attachedOn], set the first time something uses it.
 *
 * Rules it keeps: a photo has its stored sizes and no length or cover; a video has its length, its cover in
 * [variants] and its file at [videoPath]. It can't know whether its files are really on the disk, nor what it is
 * attached to.
 *
 * @property variants a photo's stored sizes, or a video's cover frame sizes, smallest first.
 * @property storageDir its folder relative to the media root (`2026/10/med_…`); every path is inside it.
 * @property bytes the size of its main file (the largest photo, or the video).
 */
class MediaAsset private constructor(
    val id: MediaId,
    val purpose: MediaPurpose,
    val type: MediaType,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val bytes: Long,
    val blurhash: String?,
    val variants: List<MediaVariant>,
    val posterPath: String?,
    val alt: MediaAlt?,
    val storageDir: String,
    val uploadedBy: UserId,
    val uploadedOn: Instant,
    val attachedOn: Instant?,
) {
    init {
        require(width > 0 && height > 0 && bytes > 0) { "MediaAsset ${id.value}: bad size ${width}x$height, $bytes bytes" }
        require(variants.isNotEmpty() && variants == variants.sortedBy { it.width }) { "MediaAsset ${id.value}: variants must be smallest first" }
        require(MediaVariant.isRelativePath(storageDir)) { "MediaAsset ${id.value}: bad folder '$storageDir'" }
        require(variants.all { it.path.startsWith("$storageDir/") }) { "MediaAsset ${id.value}: a variant outside its folder" }
        when (type) {
            MediaType.IMAGE -> require(durationSec == null && posterPath == null && mimeType == IMAGE_MIME) { "MediaAsset ${id.value}: a photo with video parts" }
            MediaType.VIDEO -> require(
                durationSec != null && durationSec > 0 && posterPath != null && posterPath.startsWith("$storageDir/") && mimeType == VIDEO_MIME,
            ) { "MediaAsset ${id.value}: a video without its length or cover" }
        }
    }

    /** Where its main file is: the largest photo, or the video. */
    val mainPath: String get() = if (type == MediaType.VIDEO) videoPath else variants.last().path

    /** A video's file. */
    val videoPath: String get() = "$storageDir/$VIDEO_FILE"

    /** Its smallest stored size: a photo's thumbnail, a video's smaller cover. */
    val thumbnailPath: String get() = variants.first().path

    /**
     * The stored size to show at [width] pixels: the largest one not wider than it (`w800` for 800), or the smallest
     * when every size is wider. For a video, one of its covers.
     */
    fun pathFor(width: Int): String = (variants.lastOrNull { it.width <= width } ?: variants.first()).path

    /** Whether something uses it; an attached file is never removed. */
    val isAttached: Boolean get() = attachedOn != null

    override fun toString() = "MediaAsset(${id.value})"

    override fun equals(other: Any?) = other is MediaAsset && other.id == id

    override fun hashCode() = id.hashCode()

    companion object {
        /** Every stored photo is webp; every stored video is mp4. */
        const val IMAGE_MIME = "image/webp"
        const val VIDEO_MIME = "video/mp4"
        const val VIDEO_FILE = "video.mp4"

        /** A file just processed and stored by [uploadedBy], attached to nothing yet. */
        fun upload(
            id: MediaId,
            purpose: MediaPurpose,
            type: MediaType,
            width: Int,
            height: Int,
            durationSec: Int?,
            bytes: Long,
            blurhash: String?,
            variants: List<MediaVariant>,
            posterPath: String?,
            alt: MediaAlt?,
            storageDir: String,
            uploadedBy: UserId,
            now: Instant,
        ) = MediaAsset(
            id, purpose, type, if (type == MediaType.VIDEO) VIDEO_MIME else IMAGE_MIME, width, height, durationSec, bytes, blurhash,
            variants.sortedBy { it.width }, posterPath, alt, storageDir, uploadedBy, now, attachedOn = null,
        )

        /** For a row read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            id: MediaId,
            purpose: MediaPurpose,
            type: MediaType,
            mimeType: String,
            width: Int,
            height: Int,
            durationSec: Int?,
            bytes: Long,
            blurhash: String?,
            variants: List<MediaVariant>,
            posterPath: String?,
            alt: MediaAlt?,
            storageDir: String,
            uploadedBy: UserId,
            uploadedOn: Instant,
            attachedOn: Instant?,
        ) = MediaAsset(
            id, purpose, type, mimeType, width, height, durationSec, bytes, blurhash, variants, posterPath, alt, storageDir, uploadedBy,
            uploadedOn, attachedOn,
        )
    }
}
