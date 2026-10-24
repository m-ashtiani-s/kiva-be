package com.kiva.application.usecase.product.usecase.command

import com.kiva.domain.aggregate.wishlist.entity.Wishlist
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.application.port.AuditChange
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.MutableClock
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.event.ProductPublished
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductStatusUseCasesTest {

    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val clock = MutableClock(now)
    private val staff = PanelActor(UserId.constructFromPersisted(1), "10.0.0.1")

    private val transactionManager = MockTransactionManager()
    private val products = InMemoryProducts()
    private val ids = CountingIds()
    private val audit = RecordingAuditTrail()
    private val domain = ProductDomainService(products, ids, Catalog.categories, Catalog.materials, Catalog.colors)

    private val changeStatus = ChangeProductStatusUc(transactionManager, products, audit, clock)
    private val wishlists = WishlistRemovals()
    private val archive = ArchiveProductUc(transactionManager, products, wishlists, audit, clock)
    private val publishScheduled = PublishScheduledProductUc(transactionManager, products, clock)

    private var made = 0

    /** A new product through CreateProductUc, in [status], with [colors] (none: a draft without colors). */
    private suspend fun product(status: String = "DRAFT", colors: List<String> = listOf("lilac")): ProductId {
        made++
        val fields = ProductInput.Fields(
            name = "کیف $made", slug = "bag-$made", sku = null, categoryId = 1, bagType = "HOBO", materialId = 11, materialDescription = null,
            price = 1_890_000, compareAtPrice = null, shortDescription = null, description = null, highlights = null, specs = null, tags = null,
            newUntil = null, maxPerOrder = null, returnable = null, reservable = null, sortPriority = null, seo = null,
        )
        val create = CreateProductUc(
            transactionManager, domain, products, ReadModel(), ProductInput(HtmlSanitizer { it }), MediaAttachments(InMemoryMedia()),
            InventoryDomainService(InMemoryInventories()), InMemoryInventories(), RecordingAuditTrail(), clock,
        )
        return ProductId.constructFromPersisted(
            create.execute(CreateProductUc.Cmd(fields, status, colors.map { ProductInput.Variant(it) }, null)).getOrNull()!!.productId,
        )
    }

    private fun stored(id: ProductId): Product = products.get(id)!!

    private suspend fun change(id: ProductId, status: String?, publishAt: String? = null) =
        changeStatus.execute(ChangeProductStatusUc.Cmd(id.value.toString(), status, publishAt, staff))

    private val inTenMinutes = now.plus(Duration.ofMinutes(10))

    @Nested
    inner class TheTable {
        @Test
        fun `DRAFT to ACTIVE without publishAt, or with one passed, publishes it - a schedule goes with it`() = runTest {
            val plain = product()
            val passed = product()
            val scheduled = product().also { change(it, "ACTIVE", inTenMinutes.toString()) }

            assertEquals(Result.success(plain), change(plain, "ACTIVE"))
            assertEquals(Result.success(passed), change(passed, "ACTIVE", now.minusSeconds(60).toString()))
            assertEquals(Result.success(scheduled), change(scheduled, "ACTIVE"))

            assertEquals(listOf(ProductStatus.ACTIVE, ProductStatus.ACTIVE, ProductStatus.ACTIVE), listOf(plain, passed, scheduled).map { stored(it).status })
            assertNull(stored(scheduled).publishAt)
            assertEquals(AuditAction.PRODUCT_PUBLISHED, audit.recorded.last().action)
            assertEquals(listOf(AuditChange("status", "DRAFT", "ACTIVE"), AuditChange("publishAt", inTenMinutes.toString(), null)), audit.recorded.last().changes)
        }

        @Test
        fun `DRAFT to ACTIVE without an active color is NO_ACTIVE_VARIANT, scheduled or not`() = runTest {
            val bare = product(colors = emptyList())

            assertEquals(Result.failure(ChangeProductStatusUc.Failure.NoActiveVariant), change(bare, "ACTIVE"))
            assertEquals(Result.failure(ChangeProductStatusUc.Failure.NoActiveVariant), change(bare, "ACTIVE", inTenMinutes.toString()))
        }

        @Test
        fun `DRAFT to ACTIVE with a future publishAt schedules it and it stays a draft - more than 365 days is PUBLISH_AT_TOO_FAR`() = runTest {
            val id = product()

            change(id, "ACTIVE", "2026-10-06T11:40:00+03:30")
            val tooFar = change(id, "ACTIVE", now.plus(Duration.ofDays(366)).toString()).failureOrNull()!!

            assertEquals(ProductStatus.DRAFT, stored(id).status)
            assertEquals(inTenMinutes, stored(id).publishAt)
            assertEquals(AuditAction.PRODUCT_PUBLISH_SCHEDULED, audit.recorded.single().action)
            assertEquals(listOf("PUBLISH_AT_TOO_FAR" to "publishAt"), tooFar.causes.map { it.code to it.field })
        }

        @Test
        fun `DRAFT to DRAFT calls a schedule off - without one it is PRODUCT_ALREADY_DRAFT`() = runTest {
            val id = product().also { change(it, "ACTIVE", inTenMinutes.toString()) }

            assertEquals(Result.success(id), change(id, "DRAFT"))
            assertNull(stored(id).publishAt)
            assertEquals(AuditAction.PRODUCT_PUBLISH_UNSCHEDULED, audit.recorded.last().action)
            assertEquals(Result.failure(ChangeProductStatusUc.Failure.AlreadyDraft), change(id, "DRAFT"))
        }

        @Test
        fun `ACTIVE to ACTIVE is PRODUCT_ALREADY_ACTIVE`() = runTest {
            assertEquals(Result.failure(ChangeProductStatusUc.Failure.AlreadyActive), change(product("ACTIVE"), "ACTIVE"))
        }

        @Test
        fun `ACTIVE to DRAFT takes it out of the shop`() = runTest {
            val id = product("ACTIVE")

            change(id, "DRAFT")

            assertEquals(ProductStatus.DRAFT, stored(id).status)
            assertEquals(AuditAction.PRODUCT_UNPUBLISHED, audit.recorded.single().action)
        }

        @Test
        fun `DRAFT or ACTIVE to ARCHIVED archives it`() = runTest {
            val draft = product()
            val active = product("ACTIVE")

            change(draft, "ARCHIVED")
            change(active, "ARCHIVED")

            assertEquals(listOf(ProductStatus.ARCHIVED, ProductStatus.ARCHIVED), listOf(stored(draft).status, stored(active).status))
            assertEquals(listOf(AuditAction.PRODUCT_ARCHIVED, AuditAction.PRODUCT_ARCHIVED), audit.recorded.map { it.action })
        }

        @Test
        fun `ARCHIVED to DRAFT or ACTIVE is INVALID_STATUS_TRANSITION with from and to`() = runTest {
            val id = product("ARCHIVED")

            val toDraft = change(id, "DRAFT").failureOrNull()!!
            val toActive = change(id, "ACTIVE").failureOrNull()!!

            assertEquals(mapOf<String, Any>("from" to "ARCHIVED", "to" to "DRAFT"), toDraft.meta)
            assertEquals(listOf("INVALID_STATUS_TRANSITION", "INVALID_STATUS_TRANSITION"), listOf(toDraft.code, toActive.code))
            assertEquals(FailureKind.BUSINESS_RULE, toActive.kind)
        }

        @Test
        fun `ARCHIVED to ARCHIVED is PRODUCT_ALREADY_ARCHIVED`() = runTest {
            assertEquals(Result.failure(ChangeProductStatusUc.Failure.AlreadyArchived), change(product("ARCHIVED"), "ARCHIVED"))
        }

        @Test
        fun `publishAt with DRAFT or ARCHIVED, or on a product in the shop, is PUBLISH_AT_NOT_ALLOWED on publishAt`() = runTest {
            val draft = product()
            val active = product("ACTIVE")

            listOf(change(draft, "DRAFT", inTenMinutes.toString()), change(draft, "ARCHIVED", inTenMinutes.toString()), change(active, "ACTIVE", inTenMinutes.toString()))
                .forEach { assertEquals(listOf("PUBLISH_AT_NOT_ALLOWED" to "publishAt"), it.failureOrNull()!!.causes.map { c -> c.code to c.field }) }
            assertEquals(ProductStatus.DRAFT, stored(draft).status)
            assertTrue(audit.recorded.isEmpty())
        }

        @Test
        fun `the form first - a missing or unknown status, a publishAt that is not a moment - then a product nobody has is 404`() = runTest {
            val id = product()

            assertEquals(listOf("status" to "FIELD_REQUIRED"), change(id, null).failureOrNull()!!.causes.map { it.field to it.code })
            assertEquals(listOf("status" to "PRODUCT_STATUS_INVALID"), change(id, "LIVE").failureOrNull()!!.causes.map { it.field to it.code })
            assertEquals(listOf("publishAt" to "DATE_TIME_INVALID"), change(id, "ACTIVE", "tomorrow").failureOrNull()!!.causes.map { it.field to it.code })
            assertEquals(Result.failure(ChangeProductStatusUc.Failure.NotFound), change(ProductId.constructFromPersisted(99), "ACTIVE"))
            assertEquals(
                Result.failure(ChangeProductStatusUc.Failure.NotFound),
                changeStatus.execute(ChangeProductStatusUc.Cmd("abc", "ACTIVE", null, staff)),
            )
        }
    }

    @Nested
    inner class Archiving {
        @Test
        fun `DELETE archives with one row - again it is done, with no event and no row`() = runTest {
            val id = product("ACTIVE")

            assertEquals(Result.success(Unit), archive.execute(ArchiveProductUc.Cmd(id.value.toString(), staff)))
            val version = stored(id).version
            assertEquals(Result.success(Unit), archive.execute(ArchiveProductUc.Cmd(id.value.toString(), staff)))

            assertEquals(ProductStatus.ARCHIVED, stored(id).status)
            assertEquals(version, stored(id).version)
            assertEquals(listOf(AuditAction.PRODUCT_ARCHIVED), audit.recorded.map { it.action })
            assertEquals(listOf(id), wishlists.removed, "out of every wishlist, once")
            assertEquals(Result.failure(ArchiveProductUc.Failure.NotFound), archive.execute(ArchiveProductUc.Cmd("99", staff)))
        }
    }

    /** Only what archiving does to wishlists: which products left them. */
    private class WishlistRemovals : WishlistRepository {
        val removed = mutableListOf<ProductId>()
        override fun deleteProduct(productId: ProductId) {
            removed += productId
        }
        override fun lockFor(userId: UserId): Wishlist = error("not used")
        override fun find(userId: UserId): Wishlist = error("not used")
        override fun save(wishlist: Wishlist) = error("not used")
        override fun visibleIdsOf(userId: UserId): List<ProductId> = error("not used")
        override fun countVisible(userId: UserId): Int = error("not used")
        override fun idsAmong(userId: UserId, productIds: Collection<ProductId>): Set<ProductId> = error("not used")
        override fun deleteAllOf(userId: UserId): Int = error("not used")
    }

    @Nested
    inner class TheJob {
        @Test
        fun `a due draft is published at the moment the job runs, as the system`() = runTest {
            val id = product().also { change(it, "ACTIVE", inTenMinutes.toString()) }
            clock.advance(Duration.ofMinutes(11))

            assertEquals(Result.success(PublishScheduledProductUc.Outcome.PUBLISHED), publishScheduled.execute(PublishScheduledProductUc.Cmd(id)))

            val published = products.streams.getValue(id).last() as ProductPublished
            assertEquals(clock.instant(), published.occurredOn)
            assertEquals(UserId.SYSTEM, published.occurredByUserId)
            assertNull(stored(id).publishAt)
        }

        @Test
        fun `not due yet, no longer scheduled or no longer a draft - nothing`() = runTest {
            val notYet = product().also { change(it, "ACTIVE", inTenMinutes.toString()) }
            val calledOff = product().also { change(it, "ACTIVE", inTenMinutes.toString()) }.also { change(it, "DRAFT") }
            val nobody = ProductId.constructFromPersisted(99)

            listOf(notYet, calledOff, nobody).forEach {
                assertEquals(Result.success(PublishScheduledProductUc.Outcome.NOTHING), publishScheduled.execute(PublishScheduledProductUc.Cmd(it)))
            }
            assertEquals(ProductStatus.DRAFT, stored(notYet).status)
        }

        @Test
        fun `a draft that lost its last active color is not published - its schedule is dropped`() = runTest {
            val id = product().also { change(it, "ACTIVE", inTenMinutes.toString()) }
            stored(id).also { it.deactivateVariant(it.variants.single().id, staff.userId, now).getOrNull()!! }.also(products::save)
            clock.advance(Duration.ofMinutes(11))

            assertEquals(Result.success(PublishScheduledProductUc.Outcome.SCHEDULE_DROPPED), publishScheduled.execute(PublishScheduledProductUc.Cmd(id)))
            assertEquals(ProductStatus.DRAFT, stored(id).status)
            assertNull(stored(id).publishAt)
        }
    }
}
