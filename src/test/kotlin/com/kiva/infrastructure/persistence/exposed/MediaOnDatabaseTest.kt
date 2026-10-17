package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.usecase.media.usecase.command.DeleteUnattachedMediaUc
import com.kiva.application.usecase.media.usecase.query.FindUnattachedMediaUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.job.PanelJobs
import com.kiva.infrastructure.media.DiskMediaStorage
import com.kiva.infrastructure.media.RandomMediaIds
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import com.kiva.application.usecase.audit.usecase.command.DeleteOldAuditEntriesUc
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * media_tbl (V22__media.sql), MediaRepositoryImpl, and the nightly clean-up of uploads nothing used: rows and folders
 * of files older than 48 hours go, attached ones and fresh ones stay.
 */
class MediaOnDatabaseTest {

    private val db = TestDatabase.database
    private val repository = MediaRepositoryImpl()
    private val ids = RandomMediaIds()
    private val made = mutableListOf<MediaId>()
    private val now = Instant.parse("2036-03-06T09:00:00Z")

    @TempDir
    lateinit var root: Path

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) { MediaTbl.deleteWhere { MediaTbl.mediaId inList made.map { it.value } } }
    }

    private fun photo(uploadedOn: Instant = now, alt: String? = null): MediaAsset {
        val id = ids.next().also { made += it }
        val dir = "2036/03/${id.value}"
        return MediaAsset.upload(
            id, MediaPurpose.PRODUCT, MediaType.IMAGE, 1600, 1200, null, 150_000, "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
            listOf(160, 800, 1600).map { MediaVariant.of(it, "$dir/w$it.webp") }, null, alt?.let(MediaAlt::constructFromPersisted), dir,
            UserId.constructFromPersisted(7), uploadedOn,
        )
    }

    /** [asset] stored, with its folder on the disk. */
    private fun stored(asset: MediaAsset): MediaAsset {
        transaction(db) { repository.insert(asset) }
        Files.createDirectories(root.resolve(asset.storageDir))
        asset.variants.forEach { Files.write(root.resolve(it.path), byteArrayOf(1)) }
        return asset
    }

    @Test
    fun `a stored photo reads back the same, sizes, alt and all`() {
        val asset = stored(photo(alt = "کیف یاسی"))

        val read = transaction(db) { repository.find(asset.id) } ?: error("not stored")

        assertEquals(asset.variants, read.variants)
        assertEquals("کیف یاسی", read.alt?.value)
        assertEquals(listOf(asset.width, asset.height, asset.bytes.toInt()), listOf(read.width, read.height, read.bytes.toInt()))
        assertEquals(asset.uploadedOn, read.uploadedOn)
        assertNull(read.attachedOn)
        assertEquals(asset.storageDir, read.storageDir)
    }

    @Test
    fun `marking attached sets it the first time only, and counts the ones that exist`() {
        val asset = stored(photo())

        val first = transaction(db) { repository.markAttached(listOf(asset.id, MediaId.constructFromPersisted("med_" + "Z".repeat(22))), now) }
        transaction(db) { repository.markAttached(listOf(asset.id), now.plusSeconds(60)) }

        assertEquals(1, first)
        assertEquals(now, transaction(db) { repository.find(asset.id) }?.attachedOn)
    }

    @Test
    fun `the database refuses a video without its length or cover, a bad id, or a folder outside the pattern`() {
        val asset = stored(photo())
        listOf(
            { MediaTbl.update({ MediaTbl.mediaId eq asset.id.value }) { it[type] = "VIDEO" } },
            { MediaTbl.update({ MediaTbl.mediaId eq asset.id.value }) { it[mediaId] = "med_short" } },
            { MediaTbl.update({ MediaTbl.mediaId eq asset.id.value }) { it[storageDir] = "../etc" } },
        ).forEach { change ->
            transaction(db) {
                assertFailsWith<ExposedSQLException> { change() }
                rollback()
            }
        }
    }

    @Test
    fun `the clean-up removes an upload of 49 hours with its folder - one of 47 hours and an attached one stay`() = runBlocking<Unit> {
        val clock = MutableClock(now)
        val old = stored(photo(uploadedOn = now.minus(Duration.ofHours(49))))
        val fresh = stored(photo(uploadedOn = now.minus(Duration.ofHours(47))))
        val attached = stored(photo(uploadedOn = now.minus(Duration.ofHours(100))))
        transaction(db) { repository.markAttached(listOf(attached.id), now.minus(Duration.ofHours(99))) }
        val transactions = ExposedTransactionManager(db)
        val storage = DiskMediaStorage(root)
        val jobs = PanelJobs(
            DeleteOldAuditEntriesUc(transactions, AuditTrailImpl(), clock),
            FindUnattachedMediaUc(transactions, repository, clock),
            DeleteUnattachedMediaUc(transactions, repository, storage, clock),
        )

        jobs.deleteUnattachedMedia()

        assertNull(transaction(db) { repository.find(old.id) })
        assertFalse(Files.exists(root.resolve(old.storageDir)))
        listOf(fresh, attached).forEach {
            assertEquals(it.id, transaction(db) { repository.find(it.id) }?.id)
            assertTrue(Files.exists(root.resolve(it.mainPath)))
        }
    }

    @Test
    fun `an upload attached after it was found stays - the delete itself checks`() = runBlocking<Unit> {
        val clock = MutableClock(now)
        val asset = stored(photo(uploadedOn = now.minus(Duration.ofHours(49))))
        val transactions = ExposedTransactionManager(db)
        val found = FindUnattachedMediaUc(transactions, repository, clock).execute(FindUnattachedMediaUc.Qry()).getOrNull().orEmpty()
        transaction(db) { repository.markAttached(listOf(asset.id), now) }

        found.forEach { DeleteUnattachedMediaUc(transactions, repository, DiskMediaStorage(root), clock).execute(DeleteUnattachedMediaUc.Cmd(it)) }

        assertTrue(asset.id in found)
        assertEquals(asset.id, transaction(db) { repository.find(asset.id) }?.id)
        assertTrue(Files.exists(root.resolve(asset.mainPath)))
    }
}
