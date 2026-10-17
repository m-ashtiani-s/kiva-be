package com.kiva.application.usecase.media

import com.kiva.application.port.MediaProcessingException
import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.MediaStorage
import com.kiva.application.port.ProcessedFile
import com.kiva.application.port.ProcessedMedia
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.ImageFormat
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.media.model.valueobject.VideoContainer
import com.kiva.domain.aggregate.media.repository.MediaIdGenerator
import com.kiva.domain.aggregate.media.repository.MediaRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The upload's order: the form first (nothing is read or processed for a bad one), then what the file is, then the
 * processing, the files moved into place, and the row; a failing row leaves no folder behind.
 */
class UploadMediaUcTest {

    private val transactions = MockTransactionManager()
    private val clock = Clock.fixed(Instant.parse("2036-03-06T09:00:00Z"), ZoneOffset.UTC)
    private val id = MediaId.constructFromPersisted("med_5Hq2xT9aLmQ0vB3nR7sK1w")
    private val by = UserId.constructFromPersisted(7)
    private val file = UploadMediaUc.UploadedFile(Paths.get("upload-1.bin"), 2_000_000)

    /** Says what it was told the file is; makes the photo sizes or the video's covers. */
    private class FakeProcessor(var probe: MediaProbe = MediaProbe.Image(ImageFormat.JPEG, 4000, 3000)) : MediaProcessor {
        var probed = 0
        var processed = 0
        var fail = false

        override suspend fun probe(file: Path): MediaProbe = probe.also { probed++ }

        override suspend fun process(file: Path, probe: MediaProbe, into: Path): ProcessedMedia {
            processed++
            if (fail) throw MediaProcessingException("media processing failed: vips exited with 1")
            return when (probe) {
                is MediaProbe.Image -> ProcessedMedia(1600, 1200, null, 150_000, "LKO2?U%2Tw=w]~RBVZRi};RPxuwH", listOf(160, 480, 800, 1200, 1600).map { ProcessedFile(it, "w$it.webp") }, null)
                is MediaProbe.Video -> ProcessedMedia(1280, 720, probe.durationSec, 3_000_000, null, listOf(480, 800).map { ProcessedFile(it, "poster-w$it.webp") }, "poster-w800.webp")
                MediaProbe.Unsupported -> error("never")
            }
        }
    }

    /** Remembers what was done to the disk, in order. */
    private class RecordingStorage : MediaStorage {
        val done = mutableListOf<String>()
        override fun newWorkDir(): Path = Paths.get("work").also { done += "new" }
        override fun commit(workDir: Path, storageDir: String) { done += "commit $storageDir" }
        override fun discard(workDir: Path) { done += "discard" }
        override fun delete(storageDir: String) { done += "delete $storageDir" }
    }

    private class Rows(var fail: Boolean = false) : MediaRepository {
        val stored = mutableListOf<MediaAsset>()
        override fun find(id: MediaId) = stored.firstOrNull { it.id == id }
        override fun findAll(ids: Collection<MediaId>) = stored.filter { it.id in ids }
        override fun insert(asset: MediaAsset) {
            if (fail) throw IllegalStateException("the database is down")
            stored += asset
        }
        override fun markAttached(ids: Collection<MediaId>, now: Instant) = 0
        override fun findUnattachedBefore(before: Instant, limit: Int) = emptyList<MediaId>()
        override fun deleteIfUnattached(id: MediaId, before: Instant) = false
    }

    private val processor = FakeProcessor()
    private val storage = RecordingStorage()
    private val rows = Rows()
    private val useCase = UploadMediaUc(transactions, processor, storage, rows, object : MediaIdGenerator { override fun next() = id }, clock)

    private fun command(files: List<UploadMediaUc.UploadedFile> = listOf(file), purpose: String? = "PRODUCT", alt: String? = null) =
        UploadMediaUc.Cmd(files, purpose, alt, by)

    @Test
    fun `a photo is processed, moved into its month's folder, stored, and comes back with its sizes`() = runTest {
        val media = useCase.execute(command(alt = " کیف یاسی ")).getOrNull()

        val dir = "2036/03/${id.value}"
        assertEquals(listOf("new", "commit $dir"), storage.done)
        assertEquals(id.value, media?.id)
        assertEquals(MediaType.IMAGE, media?.type)
        assertEquals("$dir/w1600.webp", media?.mainPath)
        assertEquals("$dir/w160.webp", media?.thumbnailPath)
        assertEquals(listOf(160, 480, 800, 1200, 1600), media?.variants?.map { it.width })
        assertEquals(1600 to 1200, media?.width to media?.height)
        assertEquals("کیف یاسی", media?.alt)
        val row = rows.stored.single()
        assertEquals(by, row.uploadedBy)
        assertEquals(clock.instant(), row.uploadedOn)
        assertEquals(null, row.attachedOn)
        assertEquals(1, transactions.transactionsOpened)
    }

    @Test
    fun `a video comes back with its length and covers`() = runTest {
        processor.probe = MediaProbe.Video(VideoContainer.MP4, VideoCodec.H264, 1280, 720, 24)

        val media = useCase.execute(command()).getOrNull()

        assertEquals("2036/03/${id.value}/video.mp4", media?.mainPath)
        assertEquals("2036/03/${id.value}/poster-w800.webp", media?.posterPath)
        assertEquals(24, media?.durationSec)
    }

    @Test
    fun `every bad field is reported at once, and nothing is read, processed or opened`() = runTest {
        val failure = useCase.execute(command(files = emptyList(), purpose = "RETURN_EVIDENCE", alt = "ب".repeat(201))).failureOrNull()

        assertEquals(
            listOf("file" to "FIELD_REQUIRED", "purpose" to "MEDIA_PURPOSE_INVALID", "alt" to "MEDIA_ALT_TOO_LONG"),
            failure?.causes?.map { it.field to it.code },
        )
        assertEquals(0, processor.probed)
        assertEquals(emptyList(), storage.done)
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `two files is FILE_COUNT_INVALID on file - no purpose is FIELD_REQUIRED on purpose`() = runTest {
        val failure = useCase.execute(command(files = listOf(file, file), purpose = null)).failureOrNull()

        assertEquals(listOf("file" to "FILE_COUNT_INVALID", "purpose" to "FIELD_REQUIRED"), failure?.causes?.map { it.field to it.code })
    }

    @Test
    fun `a file the purpose doesn't take is 415, too big 413, too small 400 - none of them processed`() = runTest {
        processor.probe = MediaProbe.Video(VideoContainer.MP4, VideoCodec.H264, 1280, 720, 24)
        assertEquals("UPLOAD_TYPE_NOT_ALLOWED", useCase.execute(command(purpose = "CATEGORY")).failureOrNull()?.code)

        processor.probe = MediaProbe.Image(ImageFormat.PNG, 4000, 3000)
        val tooLarge = useCase.execute(command(files = listOf(file.copy(bytes = 16L * 1024 * 1024)))).failureOrNull()
        assertEquals("UPLOAD_TOO_LARGE" to 15L * 1024 * 1024, tooLarge?.code to tooLarge?.meta?.get("maxBytes"))

        processor.probe = MediaProbe.Image(ImageFormat.PNG, 200, 200)
        val tooSmall = useCase.execute(command()).failureOrNull()
        assertEquals(listOf("file" to "IMAGE_TOO_SMALL"), tooSmall?.causes?.map { it.field to it.code })

        assertEquals(0, processor.processed)
        assertEquals(emptyList(), storage.done)
    }

    @Test
    fun `an HEVC video is 415 with the hint`() = runTest {
        processor.probe = MediaProbe.Video(VideoContainer.MP4, VideoCodec.HEVC, 1920, 1080, 10)

        assertEquals("H264", useCase.execute(command()).failureOrNull()?.meta?.get("hint"))
    }

    @Test
    fun `a row that can't be written takes its folder with it`() = runTest {
        rows.fail = true

        assertFailsWith<IllegalStateException> { useCase.execute(command()) }

        val dir = "2036/03/${id.value}"
        assertEquals(listOf("new", "commit $dir", "delete $dir"), storage.done)
    }

    @Test
    fun `a tool that fails leaves no work folder and no row`() = runTest {
        processor.fail = true

        assertFailsWith<MediaProcessingException> { useCase.execute(command()) }

        assertEquals(listOf("new", "discard"), storage.done)
        assertTrue(rows.stored.isEmpty())
    }
}
