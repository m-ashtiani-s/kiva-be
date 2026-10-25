package com.kiva.application.usecase.media

import com.kiva.application.port.MediaProcessingException
import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.MediaStorage
import com.kiva.application.port.ProcessedFile
import com.kiva.application.port.ProcessedMedia
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.common.railway.FailureKind
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The upload's order: the form first (nothing is read or processed for a bad one), a customer's cap, then what the file
 * is, then the processing, the files moved into place, and the row (a customer's counted again under their lock); a
 * failing or refused row leaves no folder behind.
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

        /** Uploads made elsewhere: times of the user's earlier files. */
        val earlier = mutableListOf<Instant>()

        /** What the second count sees on top of [earlier], as if uploads sent at once were stored meanwhile. */
        var arrivedMeanwhile = 0
        val done = mutableListOf<String>()
        override fun find(id: MediaId) = stored.firstOrNull { it.id == id }
        override fun findAll(ids: Collection<MediaId>) = stored.filter { it.id in ids }
        override fun insert(asset: MediaAsset) {
            if (fail) throw IllegalStateException("the database is down")
            stored += asset
            done += "insert"
        }
        override fun markAttached(ids: Collection<MediaId>, now: Instant) = 0
        override fun findUnattachedBefore(before: Instant, limit: Int) = emptyList<MediaId>()
        override fun deleteIfUnattached(id: MediaId, before: Instant) = false
        override fun uploadTimesOf(userId: UserId, since: Instant): List<Instant> {
            val counted = if (done.contains("lock")) earlier + List(arrivedMeanwhile) { since.plusSeconds(1) } else earlier
            done += "count"
            return counted.filter { it.isAfter(since) }
        }
        override fun lockUploadsOf(userId: UserId) {
            done += "lock"
        }
    }

    private val processor = FakeProcessor()
    private val storage = RecordingStorage()
    private val rows = Rows()
    private val useCase = UploadMediaUc(transactions, processor, storage, rows, object : MediaIdGenerator { override fun next() = id }, clock)

    private fun command(files: List<UploadMediaUc.UploadedFile> = listOf(file), purpose: String? = "PRODUCT", alt: String? = null) =
        UploadMediaUc.Cmd(files, purpose, alt, MediaUploader.Staff(by))

    private fun customerCommand(purpose: String? = "RETURN_EVIDENCE") = UploadMediaUc.Cmd(listOf(file), purpose, null, MediaUploader.Customer(by))

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
    fun `staff uploads are never counted nor locked`() = runTest {
        rows.earlier += List(30) { clock.instant().minusSeconds(60) }

        assertEquals(id.value, useCase.execute(command()).getOrNull()?.id)

        assertEquals(listOf("insert"), rows.done)
    }

    @Test
    fun `a customer uploads with their purposes - counted before the processing, then locked and counted again before the row`() = runTest {
        rows.earlier += List(19) { clock.instant().minusSeconds(60) }

        val media = useCase.execute(customerCommand("REVIEW_PHOTO")).getOrNull()

        assertEquals(id.value, media?.id)
        assertEquals(listOf("count", "lock", "count", "insert"), rows.done)
        assertEquals(2, transactions.transactionsOpened)
    }

    @Test
    fun `a staff purpose from a customer is 400 MEDIA_PURPOSE_INVALID, before anything is counted`() = runTest {
        val failure = useCase.execute(customerCommand("PRODUCT")).failureOrNull()

        assertEquals(listOf("purpose" to "MEDIA_PURPOSE_INVALID"), failure?.causes?.map { it.field to it.code })
        assertEquals(emptyList(), rows.done)
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `the 21st upload of the hour is 429 with the wait, and nothing is probed, processed or stored`() = runTest {
        // 20 in the last hour, the oldest 50 minutes ago: 10 minutes to wait.
        rows.earlier += List(20) { clock.instant().minus(Duration.ofMinutes(50)).plusSeconds(it.toLong()) }

        val failure = useCase.execute(customerCommand()).failureOrNull()

        assertEquals("RATE_LIMITED" to FailureKind.RATE_LIMITED, failure?.code to failure?.kind)
        assertEquals(600L, failure?.meta?.get("retryAfterSeconds"))
        assertEquals(0, processor.probed)
        assertEquals(emptyList(), storage.done)
        assertTrue(rows.stored.isEmpty())
    }

    @Test
    fun `uploads an hour old don't count`() = runTest {
        rows.earlier += List(20) { clock.instant().minus(Duration.ofMinutes(61)) }

        assertEquals(id.value, useCase.execute(customerCommand()).getOrNull()?.id)
    }

    @Test
    fun `uploads stored meanwhile reach the cap at the second count - 429, and the folder made goes with it`() = runTest {
        rows.earlier += List(19) { clock.instant().minusSeconds(60) }
        rows.arrivedMeanwhile = 1

        val failure = useCase.execute(customerCommand()).failureOrNull()

        assertEquals("RATE_LIMITED", failure?.code)
        val dir = "2036/03/${id.value}"
        assertEquals(listOf("new", "commit $dir", "delete $dir"), storage.done)
        assertTrue(rows.stored.isEmpty())
    }

    @Test
    fun `a tool that fails leaves no work folder and no row`() = runTest {
        processor.fail = true

        assertFailsWith<MediaProcessingException> { useCase.execute(command()) }

        assertEquals(listOf("new", "discard"), storage.done)
        assertTrue(rows.stored.isEmpty())
    }
}
