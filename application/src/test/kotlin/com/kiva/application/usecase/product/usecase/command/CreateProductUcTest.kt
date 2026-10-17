package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.dto.CreatedProductDto
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.CreateProductUc.Cmd
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.repository.ProductCodeTakenException
import com.kiva.domain.aggregate.product.repository.ProductRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateProductUcTest {

    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val staff = PanelActor(UserId.constructFromPersisted(1), "10.0.0.1")

    private val transactionManager = MockTransactionManager()
    private val products = InMemoryProducts()
    private val ids = CountingIds()
    private val inventories = InMemoryInventories()
    private val media = InMemoryMedia()
    private val audit = RecordingAuditTrail()

    private fun useCase(
        readModel: ReadModel = ReadModel(),
        repository: ProductRepository = products,
        sanitizer: HtmlSanitizer = HtmlSanitizer { it },
    ) = CreateProductUc(
        transactionManager,
        ProductDomainService(repository, ids, Catalog.categories, Catalog.materials, Catalog.colors),
        repository,
        readModel,
        ProductInput(sanitizer),
        MediaAttachments(media),
        InventoryDomainService(inventories),
        inventories,
        audit,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun color(
        key: String,
        sku: String? = null,
        price: Long? = null,
        compareAt: Long? = null,
        isDefault: Boolean? = null,
        isActive: Boolean? = null,
        stock: Int? = null,
        barcode: String? = null,
        gallery: List<ProductInput.Media>? = null,
    ) = ProductInput.Variant(key, sku, barcode, price, compareAt, stock, isDefault, isActive, null, gallery)

    private fun fields(
        slug: String? = "mahak-shoulder-bag",
        sku: String? = null,
        specs: ProductInput.Specs = ProductInput.Specs(
            dimensions = ProductInput.Size(34.0, 26.0, 12.0),
            weightGrams = 650,
            closure = "ZIPPER",
            straps = listOf(ProductInput.Strap(type = "SHOULDER", lengthCm = 55.0)),
            occasions = listOf("DAILY", "WORK"),
        ),
    ) = ProductInput.Fields(
        name = "کیف دوشی ماهک",
        slug = slug,
        sku = sku,
        categoryId = 1,
        bagType = "HOBO",
        materialId = 11,
        materialDescription = "ساتن با سگک فلزی طلایی",
        price = 1_890_000,
        compareAtPrice = null,
        shortDescription = null,
        description = "<p>کیف دوشی جادار</p>",
        highlights = listOf("یراق‌آلات فلزی ضدزنگ"),
        specs = specs,
        tags = listOf("چرم", "روزمره"),
        newUntil = Instant.parse("2026-11-01T00:00:00Z"),
        maxPerOrder = null,
        returnable = null,
        reservable = null,
        sortPriority = null,
        seo = ProductInput.Seo("ماهک | کیوا", null, null),
    )

    private fun cmd(
        fields: ProductInput.Fields = fields(),
        status: String? = null,
        variants: List<ProductInput.Variant> = listOf(color("lilac"), color("black")),
        actor: PanelActor? = staff,
    ) = Cmd(fields, status, variants, actor)

    private fun stored(id: Long = 1): Product? = products.get(ProductId.constructFromPersisted(id))

    /** The field paths a client would get, the way the HTTP layer builds them from the failure tree. */
    private fun fieldErrors(failure: ResultFailure, parent: String? = null): List<String> {
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { fieldErrors(it, path) }
        return listOf("$path ${failure.code}")
    }

    @Nested
    inner class Creating {
        @Test
        fun `a valid request makes a draft with its colors in one transaction and answers the new ids`() = runTest {
            val result = useCase().execute(cmd())

            assertEquals(Result.success(CreatedProductDto(1, mapOf("lilac" to 101L, "black" to 102L))), result)
            val product = stored()!!
            assertEquals(ProductStatus.DRAFT, product.status)
            assertEquals(listOf("KV-1001-LILAC" to true, "KV-1001-BLACK" to false), product.variants.map { it.sku.value to it.isDefault })
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `every input reaches the product, and what is left out takes the contract's defaults`() = runTest {
            useCase().execute(cmd())

            val product = stored()!!
            val details = product.details
            assertEquals(
                listOf<Any?>(
                    "mahak-shoulder-bag", "KV-1001", "کیف دوشی ماهک", 1L, 11L, BagType.HOBO, "ساتن با سگک فلزی طلایی",
                    "<p>کیف دوشی جادار</p>", listOf("یراق‌آلات فلزی ضدزنگ"), Instant.parse("2026-11-01T00:00:00Z"), listOf("چرم", "روزمره"),
                ),
                listOf(
                    product.slug.value, product.sku.value, details.name.value, details.categoryId.value, details.materialId.value,
                    details.bagType, details.materialDescription?.value, details.description?.value, details.highlights.items, details.newUntil,
                    details.tags.items,
                ),
            )
            assertEquals(
                listOf<Any?>(34.0, 650, ClosureType.ZIPPER, 55.0, setOf(Occasion.DAILY, Occasion.WORK)),
                details.specs.let { listOf(it.dimensions?.lengthCm, it.weightGrams, it.closure, it.straps.single().lengthCm, it.occasions) },
            )
            assertEquals(SeoMeta.constructFromPersisted("ماهک | کیوا", null, false), details.seo)
            assertEquals(listOf<Any?>(MaxPerOrder.DEFAULT, true, true, 0, null), listOf(details.maxPerOrder, details.returnable, details.reservable, details.sortPriority, details.shortDescription))
            assertEquals(PriceTag.constructFromPersisted(Money.constructFromPersisted(1_890_000), null), product.priceTag)
        }

        @Test
        fun `the description is kept as the sanitizer made it, never as it was typed`() = runTest {
            useCase(sanitizer = { "<p>تمیز شده</p>" }).execute(cmd())

            assertEquals("<p>تمیز شده</p>", stored()!!.details.description?.value)
        }

        @Test
        fun `status ACTIVE puts it in the shop in the same save`() = runTest {
            useCase().execute(cmd(status = "ACTIVE"))

            assertEquals(ProductStatus.ACTIVE, stored()?.status)
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `a typed code is kept, made uppercase, and a blank one is made from the id`() = runTest {
            useCase().execute(cmd(fields(sku = "kv-mahak"), variants = listOf(color("lilac", sku = " "), color("black", sku = "kv-mahak-night"))))

            val product = stored()!!
            assertEquals(listOf("KV-MAHAK", "KV-MAHAK-LILAC", "KV-MAHAK-NIGHT"), listOf(product.sku.value) + product.variants.map { it.sku.value })
        }

        @Test
        fun `a color with its own price keeps it, crossed-out price included, and its barcode`() = runTest {
            useCase().execute(cmd(variants = listOf(color("lilac", price = 1_450_000, compareAt = 1_750_000, barcode = "6260001"), color("black"))))

            val variants = stored()!!.variants
            assertEquals(
                listOf(PriceTag.constructFromPersisted(Money.constructFromPersisted(1_450_000), Money.constructFromPersisted(1_750_000)), null),
                variants.map { it.ownPriceTag },
            )
            assertEquals(listOf("6260001", null), variants.map { it.barcode?.value })
        }

        @Test
        fun `a gallery is kept in its final order with each file's own type, and its files are marked used`() = runTest {
            val photo = media.upload(1)
            val video = media.upload(2, type = MediaType.VIDEO)
            val detail = media.upload(3)
            val gallery = listOf(
                ProductInput.Media(photo.value, "FRONT", sortOrder = 2),
                ProductInput.Media(video.value, "SIDE", sortOrder = 1),
                ProductInput.Media(detail.value, "DETAIL", isPrimary = true, alt = "سگک"),
            )

            useCase().execute(cmd(variants = listOf(color("lilac", gallery = gallery))))

            val kept = stored()!!.variants.single().media
            assertEquals(listOf(detail, video, photo), kept.map { it.mediaId })
            assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), kept.map { it.type })
            assertEquals(listOf(MediaView.DETAIL, MediaView.SIDE, MediaView.FRONT), kept.map { it.view })
            assertEquals("سگک", kept.first().alt?.value)
            assertEquals(setOf(photo, video, detail), media.attached)
        }

        @Test
        fun `the starting stock of each color is one RESTOCK in the same transaction, none for a color without`() = runTest {
            useCase().execute(cmd(variants = listOf(color("lilac", stock = 12), color("black"), color("cream", stock = 3))))

            assertEquals(
                mapOf(101L to listOf(12), 103L to listOf(3)),
                inventories.streams.mapKeys { it.key.value }.mapValues { (_, events) -> events.map { (it as StockAdjusted).delta.value } },
            )
            assertTrue(inventories.streams.values.flatten().all { (it as StockAdjusted).reason == AdjustmentReason.RESTOCK })
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `a color sent inactive is added and taken out of the shop, the next one becoming the default`() = runTest {
            useCase().execute(cmd(variants = listOf(color("lilac", isActive = false), color("black"))))

            assertEquals(listOf(false to false, true to true), stored()!!.variants.map { it.isActive to it.isDefault })
        }

        @Test
        fun `one PRODUCT_CREATED row for the panel, none for the seeder`() = runTest {
            useCase().execute(cmd())
            useCase().execute(cmd(fields(slug = "nila-bag"), actor = null))

            val row = audit.recorded.single()
            assertEquals(listOf<Any?>(AuditAction.PRODUCT_CREATED, "1", "کیف دوشی ماهک", null, staff), listOf(row.action, row.entityId, row.entityLabel, row.changes, row.actor))
            assertEquals(UserId.SYSTEM, stored(2)!!.let { products.streams.getValue(it.id).first().occurredByUserId })
        }
    }

    @Nested
    inner class Refusing {
        @Test
        fun `every wrong input is reported at once under its full path, before any transaction`() = runTest {
            val wrongSpecs = ProductInput.Specs(
                dimensions = ProductInput.Size(0.0, 26.0, 12.0),
                weightGrams = 0,
                closure = "VELCRO",
                straps = listOf(ProductInput.Strap(lengthCm = 250.0)),
                occasions = listOf("DAILY", "PICNIC"),
            )
            val wrong = cmd(
                fields(slug = "Mahak Bag", specs = wrongSpecs).copy(
                    name = " ",
                    categoryId = 0,
                    bagType = "SHOULDER",
                    price = 1_000_000,
                    compareAtPrice = 900_000,
                    highlights = List(9) { "ویژگی $it" },
                    maxPerOrder = 21,
                    tags = listOf("خوب", " "),
                ),
                status = "LIVE",
                variants = listOf(
                    color("lilac", compareAt = 2_000_000, stock = 10_001),
                    color("Lilac", price = -5, barcode = "x", gallery = listOf(ProductInput.Media("med_bad", "FRONT"), ProductInput.Media(null, "TOP"))),
                ),
            )

            val failure = useCase().execute(wrong).failureOrNull()!!

            assertEquals("VALIDATION_ERROR", failure.code)
            assertEquals(
                listOf(
                    "slug SLUG_INVALID",
                    "name PRODUCT_NAME_REQUIRED",
                    "categoryId CATEGORY_ID_INVALID",
                    "bagType BAG_TYPE_INVALID",
                    "highlights HIGHLIGHTS_TOO_MANY",
                    "specs.dimensions DIMENSIONS_INVALID",
                    "specs.closure.type CLOSURE_TYPE_INVALID",
                    "specs.straps.0.lengthCm STRAP_LENGTH_INVALID",
                    "specs.occasions.1 OCCASION_INVALID",
                    "specs.weightGrams WEIGHT_INVALID",
                    "tags.1 TAG_INVALID",
                    "maxPerOrder MAX_PER_ORDER_INVALID",
                    "compareAtPrice COMPARE_AT_PRICE_NOT_ABOVE_PRICE",
                    "status PRODUCT_STATUS_INVALID",
                    "variants.0.priceOverride PRICE_OVERRIDE_REQUIRED",
                    "variants.0.stockQuantity STOCK_QUANTITY_INVALID",
                    "variants.1.colorKey COLOR_KEY_INVALID",
                    "variants.1.barcode BARCODE_INVALID",
                    "variants.1.priceOverride MONEY_NEGATIVE",
                    "variants.1.media.0.mediaId MEDIA_ID_INVALID",
                    "variants.1.media.1.mediaId FIELD_REQUIRED",
                    "variants.1.media.1.view MEDIA_VIEW_INVALID",
                ),
                fieldErrors(failure),
            )
            assertEquals(0, transactionManager.transactionsOpened)
        }

        @Test
        fun `a gallery's own rules - a second primary, a file twice, more than 20`() = runTest {
            val first = media.upload(1)
            val twoPrimaries = listOf(ProductInput.Media(first.value, "FRONT", isPrimary = true), ProductInput.Media(media.upload(2).value, "BACK", isPrimary = true))
            val twice = listOf(ProductInput.Media(first.value, "FRONT"), ProductInput.Media(first.value, "BACK"))
            val tooMany = (1..21).map { ProductInput.Media(InMemoryMedia.mediaId(it).value, "FRONT") }

            assertEquals(listOf("variants.0.media.1.isPrimary MEDIA_PRIMARY_DUPLICATE"), fieldErrors(useCase().execute(cmd(variants = listOf(color("lilac", gallery = twoPrimaries)))).failureOrNull()!!))
            assertEquals(listOf("variants.0.media.1.mediaId MEDIA_DUPLICATE"), fieldErrors(useCase().execute(cmd(variants = listOf(color("lilac", gallery = twice)))).failureOrNull()!!))
            assertEquals(listOf("variants.0.media MEDIA_TOO_MANY"), fieldErrors(useCase().execute(cmd(variants = listOf(color("lilac", gallery = tooMany)))).failureOrNull()!!))
        }

        @Test
        fun `a file that is not an uploaded product file is refused at its place in the form`() = runTest {
            val preShipment = media.upload(1, purpose = MediaPurpose.PRE_SHIPMENT)
            val product = media.upload(2)
            val gallery = listOf(ProductInput.Media(product.value, "FRONT"), ProductInput.Media(preShipment.value, "BACK"), ProductInput.Media(InMemoryMedia.mediaId(9).value, "SIDE"))

            val failure = useCase().execute(cmd(variants = listOf(color("lilac"), color("black", gallery = gallery)))).failureOrNull()!!

            assertEquals(
                listOf("variants.1.media.1.mediaId MEDIA_PURPOSE_MISMATCH", "variants.1.media.2.mediaId MEDIA_NOT_FOUND"),
                fieldErrors(failure),
            )
            assertNull(stored())
            assertTrue(media.attached.isEmpty())
        }

        @Test
        fun `a slug in use is DUPLICATE_SLUG, found before any id is taken`() = runTest {
            val result = useCase(ReadModel(takenSlugs = setOf("mahak-shoulder-bag"))).execute(cmd())

            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSlug(Slug.constructFromPersisted("mahak-shoulder-bag"))), result)
            assertEquals(listOf(FailureKind.CONFLICT, "slug"), result.failureOrNull()?.let { listOf(it.kind, it.field) })
            assertEquals(listOf(0L, 100L), listOf(ids.productsTaken, ids.variantsTaken))
            assertTrue(products.streams.isEmpty())
        }

        @Test
        fun `a code another product has is DUPLICATE_SKU on the input that has it, even one made from the id`() = runTest {
            val takenColorCode = useCase(ReadModel(takenSkus = setOf("KV-1001-BLACK"))).execute(cmd())
            val takenProductCode = useCase(ReadModel(takenSkus = setOf("KV-1002"))).execute(cmd())

            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSku("variants.1.sku", "KV-1001-BLACK")), takenColorCode)
            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSku("sku", "KV-1002")), takenProductCode)
            assertTrue(products.streams.isEmpty())
        }

        @Test
        fun `a code another request took between the check and the save is the same 409 the check gives`() = runTest {
            val slugTaken = useCase(repository = TakenOnSave(products) { ProductCodeTakenException.SlugTaken(it.slug, RuntimeException()) })
                .execute(cmd())
            val colorCodeTaken = useCase(
                repository = TakenOnSave(products) { ProductCodeTakenException.SkuTaken(Sku.constructFromPersisted("KV-1002-BLACK"), RuntimeException()) },
            ).execute(cmd())

            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSlug(Slug.constructFromPersisted("mahak-shoulder-bag"))), slugTaken)
            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSku("variants.1.sku", "KV-1002-BLACK")), colorCodeTaken)
            assertTrue(products.streams.isEmpty())
            assertTrue(inventories.streams.isEmpty())
        }

        @Test
        fun `a color outside the palette is COLOR_NOT_FOUND at its place in the list`() = runTest {
            val result = useCase().execute(cmd(variants = listOf(color("lilac"), color("teal"))))

            assertEquals(Result.failure(CreateProductUc.Failure.ColorNotFound(ColorKey.constructFromPersisted("teal"), 1)), result)
            assertEquals("variants.1.colorKey", result.failureOrNull()?.field)
            assertNull(stored())
        }

        @Test
        fun `the same color twice is refused at its second place`() = runTest {
            val result = useCase().execute(cmd(variants = listOf(color("lilac"), color("black"), color("lilac"))))

            assertEquals(Result.failure(CreateProductUc.Failure.ColorRepeated(ColorKey.constructFromPersisted("lilac"), 2)), result)
            assertEquals("variants.2.colorKey", result.failureOrNull()?.field)
        }

        @Test
        fun `a category nobody has is CATEGORY_NOT_FOUND on categoryId`() = runTest {
            val result = useCase().execute(cmd(fields().copy(categoryId = 9)))

            assertEquals(Result.failure(CreateProductUc.Failure.CategoryNotFound(CategoryId.constructFromPersisted(9))), result)
            assertEquals(FailureKind.INVALID, result.failureOrNull()?.kind)
        }

        @Test
        fun `status ACTIVE without an active color is NO_ACTIVE_VARIANT and nothing is saved`() = runTest {
            val noColor = useCase().execute(cmd(status = "ACTIVE", variants = emptyList()))
            val onlyInactive = useCase().execute(cmd(status = "ACTIVE", variants = listOf(color("lilac", isActive = false, stock = 4))))

            assertEquals(Result.failure(CreateProductUc.Failure.NoActiveVariant), noColor)
            assertEquals(Result.failure(CreateProductUc.Failure.NoActiveVariant), onlyInactive)
            assertEquals(FailureKind.BUSINESS_RULE, noColor.failureOrNull()?.kind)
            assertNull(stored())
            assertTrue(inventories.streams.isEmpty())
            assertTrue(audit.recorded.isEmpty())
        }
    }
}
