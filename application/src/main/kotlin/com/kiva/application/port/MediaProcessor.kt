package com.kiva.application.port

import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import java.nio.file.Path

/**
 * Turns an uploaded file into what the shop stores (roadmap section 5.2), with outside tools (libvips, ffmpeg). Both
 * calls are slow and run off the request's threads; neither is ever called inside a transaction.
 */
interface MediaProcessor {

    /**
     * What [file] is, read from its content (never its name), before anything is decoded: a file that isn't a photo
     * or a video the shop takes is [MediaProbe.Unsupported]. A photo's size is as it shows, after its EXIF rotation.
     */
    suspend fun probe(file: Path): MediaProbe

    /**
     * Writes the stored files of [file], which [probe] accepted, into the empty folder [into]: a photo as webp in each
     * width (rotated, every metadata removed, the original not kept); a video as mp4 without its metadata
     * ([com.kiva.domain.aggregate.media.entity.MediaAsset.VIDEO_FILE]) and its cover frame in each poster width.
     *
     * @throws MediaProcessingException when a tool fails on a file it accepted: a broken install, not a bad upload.
     */
    suspend fun process(file: Path, probe: MediaProbe, into: Path): ProcessedMedia
}

/**
 * What [MediaProcessor.process] made.
 *
 * @property files a photo's sizes, or a video's cover sizes, each a file in the folder: its width and its name.
 * @property posterFile a video's largest cover; null for a photo.
 * @property width, height the largest stored photo's size, or the video's.
 * @property bytes the main file's size (the largest photo, or the video).
 */
data class ProcessedMedia(
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val bytes: Long,
    val blurhash: String?,
    val files: List<ProcessedFile>,
    val posterFile: String?,
)

data class ProcessedFile(val width: Int, val name: String)

/** A tool failed on a file it should have taken. Logged as an error; the client gets a 500. */
class MediaProcessingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
