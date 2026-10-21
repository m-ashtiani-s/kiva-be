package com.kiva.infrastructure.media

import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.ProcessedFile
import com.kiva.application.port.ProcessedMedia
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.ImageFormat
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.media.model.valueobject.VideoContainer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * A media processor for tests on machines without libvips and ffmpeg: it tells photos from their content with the
 * JDK's own readers (jpeg and png; a GIF is unsupported, like anything else), and videos from a made-up header
 * ([video]). It writes small placeholder files with the names the real one uses.
 */
class FakeMediaProcessor : MediaProcessor {

    override suspend fun probe(file: Path): MediaProbe {
        val head = Files.newInputStream(file).use { it.readNBytes(VIDEO_MAGIC.length) }
        if (String(head, Charsets.US_ASCII) == VIDEO_MAGIC) return videoProbeOf(Files.readString(file, Charsets.US_ASCII))
        return ImageIO.createImageInputStream(file.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return MediaProbe.Unsupported
            val format = when (reader.formatName.lowercase()) {
                "jpeg", "jpg" -> ImageFormat.JPEG
                "png" -> ImageFormat.PNG
                else -> return MediaProbe.Unsupported
            }
            reader.input = input
            MediaProbe.Image(format, reader.getWidth(0), reader.getHeight(0))
        }
    }

    override suspend fun process(file: Path, probe: MediaProbe, into: Path): ProcessedMedia = when (probe) {
        is MediaProbe.Image -> {
            val files = MediaLimitsPolicy.imageWidthsFor(probe.width).map { width ->
                Files.write(into.resolve("w$width.webp"), "webp $width".toByteArray())
                ProcessedFile(width, "w$width.webp")
            }
            val width = files.last().width
            ProcessedMedia(width, probe.height * width / probe.width, null, Files.size(into.resolve(files.last().name)), "LKO2?U%2Tw=w]~RBVZRi};RPxuwH", files, null)
        }
        is MediaProbe.Video -> {
            Files.write(into.resolve(MediaAsset.VIDEO_FILE), "mp4".toByteArray())
            val posters = MediaLimitsPolicy.POSTER_WIDTHS.map { width ->
                Files.write(into.resolve("poster-w$width.webp"), "poster $width".toByteArray())
                ProcessedFile(width, "poster-w$width.webp")
            }
            ProcessedMedia(probe.width, probe.height, probe.durationSec, 3, null, posters, posters.last().name)
        }
        MediaProbe.Unsupported -> error("An unsupported file is never processed")
    }

    companion object {
        private const val VIDEO_MAGIC = "FAKEVIDEO"

        /** A made-up video the fake recognises: `FAKEVIDEO H264 MP4 1280x720 24`, padded to [bytes]. */
        fun video(codec: VideoCodec = VideoCodec.H264, container: VideoContainer = VideoContainer.MP4, seconds: Int = 24, bytes: Int = 64): ByteArray =
            "$VIDEO_MAGIC ${codec.name} ${container.name} 1280x720 $seconds ".padEnd(bytes, '.').toByteArray(Charsets.US_ASCII)

        private fun videoProbeOf(header: String): MediaProbe {
            val (_, codec, container, size, seconds) = header.trim('.', ' ').split(' ')
            val (width, height) = size.split('x').map { it.toInt() }
            return MediaProbe.Video(VideoContainer.valueOf(container), VideoCodec.valueOf(codec), width, height, seconds.toInt())
        }

        /** A real photo of [width] x [height], as jpeg or png bytes. */
        fun photo(width: Int, height: Int, format: String = "jpeg"): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(0xC8, 0xA2, 0xC8)
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
        }

        /** A real jpeg of [width] x [height] with an EXIF block that holds a GPS position. */
        fun photoWithGps(width: Int, height: Int): ByteArray {
            val jpeg = photo(width, height)
            // TIFF, little endian: IFD0 with one entry (GPSInfo -> IFD at 26), the GPS IFD with GPSLatitudeRef "N".
            val tiff = byteArrayOf(
                0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x25, 0x88.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x01, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00, 0x4E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            )
            val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
            val length = payload.size + 2
            val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
            return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
        }
    }
}
