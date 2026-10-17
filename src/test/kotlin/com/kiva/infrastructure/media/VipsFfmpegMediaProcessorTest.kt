package com.kiva.infrastructure.media

import com.kiva.domain.aggregate.media.model.valueobject.ImageFormat
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.media.model.valueobject.VideoContainer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real processor with the real tools (libvips and ffmpeg, kiva.media.vipsBin and ffmpegBin, or on the PATH). On a
 * machine without them every test here is skipped with a message saying so, never failed: the rest of the uploads is
 * tested with FakeMediaProcessor. On the server, and on a machine with the tools, these must pass.
 *
 * Inputs are made here, not kept as files: photos with the JDK, videos with ffmpeg itself.
 */
class VipsFfmpegMediaProcessorTest {

    private val config = MediaConfig.DEFAULT.copy(
        vipsBin = System.getenv("KIVA_VIPS_BIN") ?: "vips",
        ffmpegBin = System.getenv("KIVA_FFMPEG_BIN") ?: "ffmpeg",
    )
    private val processor = VipsFfmpegMediaProcessor(config)

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun needsTheTools() {
        assumeTrue(MediaTools.available(config), "libvips and ffmpeg are not installed here (kiva.media.vipsBin / ffmpegBin); the real-tool tests are skipped")
    }

    private fun file(name: String, bytes: ByteArray): Path = dir.resolve(name).also { Files.write(it, bytes) }

    /** A jpeg of [width] x [height] with an EXIF block that holds a GPS position. */
    private fun jpegWithGps(width: Int, height: Int): ByteArray {
        val jpeg = FakeMediaProcessor.photo(width, height)
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

    private fun ffmpeg(vararg args: String): Boolean {
        val process = ProcessBuilder(listOf(config.ffmpegBin, "-v", "error", "-y") + args).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0
    }

    private fun vipsHeaderAll(file: Path): String {
        val process = ProcessBuilder(config.vipsHeaderBin, "-a", file.toString()).redirectErrorStream(true).start()
        return ByteArrayOutputStream().also { process.inputStream.copyTo(it) }.toString(Charsets.UTF_8).also { process.waitFor() }
    }

    @Test
    fun `a 4000 by 3000 jpeg with GPS becomes five webp sizes, 1600 wide at most, a blurhash, and no metadata`() = runBlocking<Unit> {
        val source = file("upload.bin", jpegWithGps(4000, 3000))
        assertTrue("gps" in vipsHeaderAll(source).lowercase(), "the test photo must carry its GPS block")

        val probe = processor.probe(source)
        assertEquals(MediaProbe.Image(ImageFormat.JPEG, 4000, 3000), probe)
        val out = Files.createDirectory(dir.resolve("out"))
        val processed = processor.process(source, probe, out)

        assertEquals(listOf(160, 480, 800, 1200, 1600), processed.files.map { it.width })
        assertEquals(1600 to 1200, processed.width to processed.height)
        assertNotNull(processed.blurhash)
        processed.files.forEach { stored ->
            val header = vipsHeaderAll(out.resolve(stored.name)).lowercase()
            assertFalse("exif" in header || "gps" in header, "${stored.name} keeps metadata: $header")
        }
        assertEquals(processed.files.map { it.name }.toSet(), Files.list(out).use { files -> files.map { it.fileName.toString() }.toList() }.toSet())
    }

    @Test
    fun `a png 400 by 320 keeps only 160 and 400`() = runBlocking<Unit> {
        val source = file("upload.bin", FakeMediaProcessor.photo(400, 320, "png"))
        val out = Files.createDirectory(dir.resolve("out"))

        val processed = processor.process(source, processor.probe(source), out)

        assertEquals(listOf(160, 400), processed.files.map { it.width })
    }

    @Test
    fun `a GIF, an SVG and a program named jpg are unsupported`() = runBlocking<Unit> {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400"/></svg>""".toByteArray()
        listOf(FakeMediaProcessor.photo(400, 400, "gif"), svg, byteArrayOf(0x4D, 0x5A) + ByteArray(2000)).forEachIndexed { i, bytes ->
            assertEquals(MediaProbe.Unsupported, processor.probe(file("upload-$i.bin", bytes)), "file $i")
        }
    }

    @Test
    fun `an H264 mp4 becomes a clean mp4 with its covers - an HEVC one is seen as HEVC`() = runBlocking<Unit> {
        val h264 = dir.resolve("h264.mp4")
        assumeTrue(ffmpeg("-f", "lavfi", "-i", "testsrc=duration=3:size=640x480:rate=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", h264.toString()), "ffmpeg has no libx264")

        val probe = processor.probe(h264)
        assertEquals(MediaProbe.Video(VideoContainer.MP4, VideoCodec.H264, 640, 480, 3), probe)
        val out = Files.createDirectory(dir.resolve("out"))
        val processed = processor.process(h264, probe, out)
        assertEquals(listOf(480, 640), processed.files.map { it.width })
        assertEquals("poster-w640.webp", processed.posterFile)
        assertEquals(3, processed.durationSec)
        assertTrue(Files.size(out.resolve("video.mp4")) > 0)

        val hevc = dir.resolve("hevc.mp4")
        if (ffmpeg("-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=10", "-c:v", "libx265", hevc.toString())) {
            assertIs<MediaProbe.Video>(processor.probe(hevc)).also { assertEquals(VideoCodec.HEVC, it.codec) }
        }
    }
}
