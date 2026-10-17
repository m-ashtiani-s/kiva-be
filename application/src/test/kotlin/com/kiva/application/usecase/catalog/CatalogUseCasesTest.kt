package com.kiva.application.usecase.catalog

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.catalog.usecase.command.CreateCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteMaterialUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateColorUc
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.UnusedCategoryRepository
import com.kiva.application.util.UnusedColorRepository
import com.kiva.application.util.UnusedMaterialRepository
import com.kiva.application.util.UnusedProductQmRepository
import com.kiva.application.util.factory.ColorFactory
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryIdGenerator
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.media.repository.MediaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog use cases without a database (step 1.3): what they refuse before any transaction, and the shared rules of
 * attaching a file. The full paths, with the database, are WebV1AdminCatalogOnDatabaseTest.
 */
class CatalogUseCasesTest {

    private val now = Instant.parse("2036-04-01T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val transactions = MockTransactionManager()
    private val actor = PanelActor(UserId.constructFromPersisted(7), "5.160.0.1")
    private val recorded = mutableListOf<AuditEntry>()
    private val auditTrail = object : AuditTrail {
        override fun record(entry: AuditEntry) {
            recorded += entry
        }
        override fun deleteRecordedBefore(before: Instant, limit: Int): Int = 0
    }

    private val lilac = ColorFactory.generate(key = "lilac")
    private val palette = object : UnusedColorRepository() {
        val saved = mutableListOf<Color>()
        override fun findByKey(key: ColorKey): Color? = (saved + lilac).lastOrNull { it.key == key }
        override fun update(color: Color) {
            saved += color
        }
    }

    @Test
    fun `a form with several mistakes is one VALIDATION_ERROR with each field - no transaction is opened`() = runTest {
        val useCase = CreateCategoryUc(
            transactions, object : UnusedCategoryRepository() {}, object : CategoryIdGenerator {
                override fun nextCategoryId() = CategoryId.constructFromPersisted(1)
            },
            palette, MediaAttachments(mockk()), auditTrail, clock,
        )
        val fields = CategoryFields("", "Not A Slug", null, "PURSE", null, "med_bad", null, null, CategoryFields.Seo("ک".repeat(71), null, null))

        val failure = useCase.execute(CreateCategoryUc.Cmd(fields, actor)).failureOrNull()

        assertEquals(
            listOf(
                "name" to "CATEGORY_NAME_REQUIRED", "slug" to "SLUG_INVALID", "iconBagType" to "BAG_TYPE_INVALID",
                "iconColorKey" to "FIELD_REQUIRED", "imageMediaId" to "MEDIA_ID_INVALID", "seo.title" to "SEO_TITLE_TOO_LONG",
            ),
            failure?.causes?.map { it.field to it.code },
        )
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `a malformed color key in the path is the same 404 as an unknown one - no transaction is opened`() = runTest {
        val useCase = UpdateColorUc(transactions, palette, object : UnusedProductQmRepository() {}, auditTrail, clock)

        val result = useCase.execute(UpdateColorUc.Cmd("Lilac!", ColorFields("lilac", "یاسی", "#C8B6E2", null, null), actor))

        assertEquals(Result.failure(UpdateColorUc.Failure.NotFound), result)
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `a swatch change logs only hex - no new name, so the search text is not written again`() = runTest {
        val useCase = UpdateColorUc(transactions, palette, object : UnusedProductQmRepository() {}, auditTrail, clock)

        val result = useCase.execute(UpdateColorUc.Cmd("lilac", ColorFields("lilac", lilac.name.value, "#d8c6f2", lilac.isLight, null), actor))

        assertEquals(Result.success(lilac.key), result)
        assertEquals("#D8C6F2", palette.saved.single().hex.value)
        assertEquals(listOf("hex"), recorded.single().changes?.map { it.field })
    }

    @Test
    fun `a material id that is not a positive number is NotFound before any transaction`() = runTest {
        val useCase = DeleteMaterialUc(transactions, object : UnusedMaterialRepository() {}, object : UnusedProductQmRepository() {}, auditTrail, clock)

        assertEquals(Result.failure(DeleteMaterialUc.Failure.NotFound), useCase.execute(DeleteMaterialUc.Cmd("0", actor)))
        assertEquals(Result.failure(DeleteMaterialUc.Failure.NotFound), useCase.execute(DeleteMaterialUc.Cmd("abc", actor)))
        assertEquals(0, transactions.transactionsOpened)
    }

    // ============ attaching files ============

    private fun asset(n: Int, purpose: MediaPurpose, type: MediaType = MediaType.IMAGE): MediaAsset {
        val id = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, 'A'))
        val dir = "2036/04/${id.value}"
        return MediaAsset.upload(
            id, purpose, type, 800, 600, if (type == MediaType.VIDEO) 12 else null, 1000, null, listOf(MediaVariant.of(800, "$dir/w800.webp")),
            if (type == MediaType.VIDEO) "$dir/poster-w800.webp" else null, null, dir, UserId.constructFromPersisted(7), now,
        )
    }

    @Test
    fun `every file of the form is checked at once - missing, uploaded for elsewhere, or a video where only photos go`() {
        val photo = asset(1, MediaPurpose.CATEGORY)
        val forProduct = asset(2, MediaPurpose.PRODUCT)
        val video = asset(3, MediaPurpose.CATEGORY, MediaType.VIDEO)
        val missing = MediaId.constructFromPersisted("med_" + "9".repeat(22))
        val media = mockk<MediaRepository>()
        every { media.findAll(any()) } returns listOf(photo, forProduct, video)
        val wanted = listOf(photo.id to "a", forProduct.id to "b", video.id to "c", missing to "d").map { MediaAttachments.Wanted(it.first, it.second) }

        val failure = MediaAttachments(media).check(wanted, MediaPurpose.CATEGORY, setOf(MediaType.IMAGE)).failureOrNull()

        assertEquals(
            listOf("b" to "MEDIA_PURPOSE_MISMATCH", "c" to "MEDIA_TYPE_NOT_ALLOWED", "d" to "MEDIA_NOT_FOUND"),
            failure?.causes?.map { it.field to it.code },
        )
    }

    @Test
    fun `files that may be attached come back by id - nothing asked is no query at all`() {
        val photo = asset(1, MediaPurpose.CATEGORY)
        val media = mockk<MediaRepository>()
        every { media.findAll(listOf(photo.id)) } returns listOf(photo)

        val checked = MediaAttachments(media).check(listOf(MediaAttachments.Wanted(photo.id, "imageMediaId")), MediaPurpose.CATEGORY, setOf(MediaType.IMAGE))

        assertEquals(mapOf(photo.id to photo), checked.getOrNull())
        assertTrue(MediaAttachments(mockk()).check(emptyList(), MediaPurpose.CATEGORY, setOf(MediaType.IMAGE)).getOrNull()!!.isEmpty())
    }
}
