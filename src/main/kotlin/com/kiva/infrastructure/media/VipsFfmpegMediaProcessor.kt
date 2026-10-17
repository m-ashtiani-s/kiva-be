package com.kiva.infrastructure.media

import com.kiva.application.port.MediaProcessingException
import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.ProcessedFile
import com.kiva.application.port.ProcessedMedia
import com.kiva.common.logging.getLogger
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.ImageFormat
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.media.model.valueobject.VideoContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * Processes uploads with libvips (photos) and ffmpeg (videos), each run as its own process with a time limit
 * (roadmap section 5.2). Arguments go to the process as a list, never through a shell, and every path is the
 * server's own. A tool's error output only goes to the log.
 *
 * - **What a file is** comes from its content: vipsheader reads a photo's header (no decoding, so a 200-megapixel
 *   "bomb" is refused before it costs anything), ffprobe reads a video's. Only jpeg, png and webp photos and mp4 or
 *   mov videos are recognised; anything else is [MediaProbe.Unsupported].
 * - **A photo** becomes webp (quality 80) in each width of MediaLimitsPolicy, rotated by its EXIF and with every
 *   metadata removed (`strip`), plus its blurhash from a 32-pixel version. The original is not kept.
 * - **A video** is copied to mp4 without being encoded again (`-c:v copy`, `+faststart`, metadata removed; its sound,
 *   if any, made AAC), and its frame at 1 second (or 0) becomes the cover in each poster width.
 */
class VipsFfmpegMediaProcessor(private val config: MediaConfig) : MediaProcessor {

    private val logger = getLogger()

    override suspend fun probe(file: Path): MediaProbe = withContext(Dispatchers.IO) {
        when (containerOf(file)) {
            Container.ISO_VIDEO -> probeVideo(file)
            Container.ISO_OTHER -> MediaProbe.Unsupported
            Container.OTHER -> probeImage(file)
        }
    }

    override suspend fun process(file: Path, probe: MediaProbe, into: Path): ProcessedMedia = withContext(Dispatchers.IO) {
        when (probe) {
            is MediaProbe.Image -> processImage(file, probe, into)
            is MediaProbe.Video -> processVideo(file, probe, into)
            MediaProbe.Unsupported -> throw IllegalArgumentException("An unsupported file is never processed")
        }
    }

    // --- what a file is ---

    private enum class Container { ISO_VIDEO, ISO_OTHER, OTHER }

    /**
     * An ISO media file (`ftyp` at byte 4) is a video for ffprobe unless its brand says HEIC/AVIF (photos the shop
     * doesn't take); anything else is left to vipsheader. This only picks the tool: the tool decides.
     */
    private fun containerOf(file: Path): Container {
        val head = ByteArray(12)
        val read = Files.newInputStream(file).use { it.readNBytes(head, 0, head.size) }
        if (read < 12 || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return Container.OTHER
        val brand = String(head, 8, 4, Charsets.ISO_8859_1)
        return if (brand in PHOTO_BRANDS) Container.ISO_OTHER else Container.ISO_VIDEO
    }

    private fun probeImage(file: Path): MediaProbe {
        val result = run(listOf(config.vipsHeaderBin, "-a", file.toString()), PROBE_TIMEOUT_SECONDS)
        if (result.exitCode != 0) return MediaProbe.Unsupported
        val fields = result.stdout.lineSequence()
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
        val format = when (fields["vips-loader"]) {
            "jpegload", "jpegload_source", "jpegload_buffer" -> ImageFormat.JPEG
            "pngload", "pngload_source", "pngload_buffer" -> ImageFormat.PNG
            "webpload", "webpload_source", "webpload_buffer" -> ImageFormat.WEBP
            else -> return MediaProbe.Unsupported
        }
        val width = fields["width"]?.toIntOrNull() ?: return MediaProbe.Unsupported
        val height = fields["height"]?.toIntOrNull() ?: return MediaProbe.Unsupported
        // EXIF orientations 5 to 8 turn the photo a quarter: as it shows, its sides swap.
        val turned = fields["orientation"]?.substringBefore(' ')?.toIntOrNull() in 5..8
        return if (turned) MediaProbe.Image(format, height, width) else MediaProbe.Image(format, width, height)
    }

    private fun probeVideo(file: Path): MediaProbe {
        val result = run(
            listOf(config.ffprobeBin, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()),
            PROBE_TIMEOUT_SECONDS,
        )
        if (result.exitCode != 0) return MediaProbe.Unsupported
        val json = runCatching { Json.parseToJsonElement(result.stdout).jsonObject }.getOrNull() ?: return MediaProbe.Unsupported
        val format = json["format"]?.jsonObject ?: return MediaProbe.Unsupported
        val formatName = format["format_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if ("mp4" !in formatName && "mov" !in formatName) return MediaProbe.Unsupported
        val video = json["streams"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.string("codec_type") == "video" }
            ?: return MediaProbe.Unsupported
        val codec = when (video.string("codec_name")) {
            "h264" -> VideoCodec.H264
            "hevc" -> VideoCodec.HEVC
            else -> VideoCodec.OTHER
        }
        val brand = format["tags"]?.jsonObject?.string("major_brand")?.trim()
        val container = if (brand == "qt") VideoContainer.MOV else VideoContainer.MP4
        val width = video["width"]?.jsonPrimitive?.intOrNull ?: return MediaProbe.Unsupported
        val height = video["height"]?.jsonPrimitive?.intOrNull ?: return MediaProbe.Unsupported
        val seconds = format.string("duration")?.toDoubleOrNull() ?: video.string("duration")?.toDoubleOrNull() ?: return MediaProbe.Unsupported
        return MediaProbe.Video(container, codec, width, height, ceil(seconds).toInt().coerceAtLeast(1))
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    // --- making the stored files ---

    private fun processImage(file: Path, probe: MediaProbe.Image, into: Path): ProcessedMedia {
        val files = MediaLimitsPolicy.imageWidthsFor(probe.width).map { width ->
            webp(file, width, into.resolve("w$width.webp"))
            ProcessedFile(width, "w$width.webp")
        }
        val largest = into.resolve(files.last().name)
        val (width, height) = dimensionsOf(largest)
        return ProcessedMedia(width, height, durationSec = null, bytes = Files.size(largest), blurhash = blurhashOf(file, into), files = files, posterFile = null)
    }

    private fun processVideo(file: Path, probe: MediaProbe.Video, into: Path): ProcessedMedia {
        val video = into.resolve(MediaAsset.VIDEO_FILE)
        tool(
            listOf(
                config.ffmpegBin, "-v", "error", "-y", "-i", file.toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                "-map_metadata", "-1", "-movflags", "+faststart", video.toString(),
            ),
        )
        val frame = into.resolve("frame.png")
        val at = if (probe.durationSec > 1) "1" else "0"
        tool(listOf(config.ffmpegBin, "-v", "error", "-y", "-ss", at, "-i", file.toString(), "-frames:v", "1", frame.toString()))
        val widths = (MediaLimitsPolicy.POSTER_WIDTHS.filter { it < probe.width } + minOf(probe.width, MediaLimitsPolicy.POSTER_WIDTHS.last())).distinct()
        val posters = widths.map { width ->
            webp(frame, width, into.resolve("poster-w$width.webp"))
            ProcessedFile(width, "poster-w$width.webp")
        }
        val blurhash = blurhashOf(frame, into)
        Files.deleteIfExists(frame)
        return ProcessedMedia(
            probe.width, probe.height, probe.durationSec, bytes = Files.size(video), blurhash = blurhash, files = posters, posterFile = posters.last().name,
        )
    }

    /** [source] as webp [width] pixels wide (never wider than it is), rotated by its EXIF, every metadata removed. */
    private fun webp(source: Path, width: Int, target: Path) {
        tool(listOf(config.vipsBin, "thumbnail", source.toString(), "$target[Q=$WEBP_QUALITY,strip]", width.toString(), "--height", "100000", "--size", "down"))
    }

    private fun dimensionsOf(file: Path): Pair<Int, Int> {
        val width = tool(listOf(config.vipsHeaderBin, "-f", "width", file.toString())).trim().toInt()
        val height = tool(listOf(config.vipsHeaderBin, "-f", "height", file.toString())).trim().toInt()
        return width to height
    }

    private fun blurhashOf(source: Path, into: Path): String? {
        val small = into.resolve("blurhash.png")
        return try {
            tool(listOf(config.vipsBin, "thumbnail", source.toString(), small.toString(), "32", "--height", "100000"))
            ImageIO.read(small.toFile())?.let { Blurhash.encode(it) }
        } finally {
            Files.deleteIfExists(small)
        }
    }

    // --- running the tools ---

    private class ProcessResult(val exitCode: Int, val stdout: String)

    /** Runs [command] and returns what it wrote; any failure is a [MediaProcessingException]. */
    private fun tool(command: List<String>): String {
        val result = run(command, PROCESS_TIMEOUT_SECONDS)
        if (result.exitCode != 0) throw MediaProcessingException("media processing failed: ${command.first()} ${command.getOrNull(1)} exited with ${result.exitCode}")
        return result.stdout
    }

    private fun run(command: List<String>, timeoutSeconds: Long): ProcessResult {
        val process = try {
            ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT)).start()
        } catch (e: IOException) {
            throw MediaProcessingException("media processing failed: ${command.first()} can't be started (is it installed? kiva.media)", e)
        }
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw MediaProcessingException("media processing failed: ${command.first()} took more than $timeoutSeconds seconds")
        }
        val errors = stderr.get(5, TimeUnit.SECONDS)
        if (process.exitValue() != 0 && errors.isNotBlank()) logger.debug("{} {}: {}", command.first(), command.getOrNull(1), errors.take(2000))
        return ProcessResult(process.exitValue(), stdout.get(5, TimeUnit.SECONDS))
    }

    companion object {
        const val WEBP_QUALITY = 80
        const val PROCESS_TIMEOUT_SECONDS = 90L
        const val PROBE_TIMEOUT_SECONDS = 20L

        /** ISO media brands of photos: HEIC from phones, and AVIF. */
        private val PHOTO_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")

        private val NULL_INPUT = java.io.File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
    }
}

/** Whether the media tools of [config] can be run here: the real-tool tests skip themselves, with a clear message, when not. */
object MediaTools {
    fun available(config: MediaConfig = MediaConfig.DEFAULT): Boolean = listOf(
        listOf(config.vipsBin, "--version"),
        listOf(config.vipsHeaderBin, "--version"),
        listOf(config.ffmpegBin, "-version"),
        listOf(config.ffprobeBin, "-version"),
    ).all { command ->
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: IOException) {
            false
        }
    }
}
