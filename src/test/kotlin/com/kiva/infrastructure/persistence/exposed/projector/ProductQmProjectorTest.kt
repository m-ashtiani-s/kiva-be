package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.policy.PricePolicy
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductSpecsPayload
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The product read model on kiva_test, written by saving products through the real repository, each step in its own
 * transaction as in the app. Every check also compares the rows with what the domain itself says (PricePolicy,
 * activeVariants, hasVideo), so the projection can't drift from the rules it copies.
 */
class ProductQmProjectorTest {

    private val db = TestDatabase.database
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    // ============ reading the read model ============

    private fun productRow(id: ProductId): ResultRow =
        transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.productId eq id.value }.single() }

    private fun colorRows(id: ProductId): List<ResultRow> = transaction(db) {
        ProductVariantQmTbl.selectAll()
            .where { ProductVariantQmTbl.productId eq id.value }
            .orderBy(ProductVariantQmTbl.variantId to SortOrder.ASC)
            .toList()
    }

    private fun colorRow(id: VariantId): ResultRow =
        transaction(db) { ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.variantId eq id.value }.single() }

    /** Every column, to compare whole rows. */
    private fun ResultRow.allColumns(): Map<String, Any?> = fieldIndex.keys.associate { it.toString() to this[it] }

    /**
     * The rows say what the domain says about [product]: the card's price tag, the active colors in display order,
     * video, and each color's price, flags and gallery. [available] is what can be sold of each color (none if absent).
     */
    private fun assertMatchesDomain(product: Product, available: Map<VariantId, Int> = emptyMap()) {
        val row = productRow(product.id)
        val cardPriceTag = PricePolicy.cardPriceTag(product)
        val displayOrder = product.activeVariants().sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

        assertEquals(
            listOf(
                cardPriceTag.price.toman, cardPriceTag.compareAtPrice?.toman, PricePolicy.discountPercent(cardPriceTag),
                product.priceTag.price.toman, product.priceTag.compareAtPrice?.toman,
                displayOrder.map { it.colorKey.value }, product.hasVideo(), displayOrder.any { (available[it.id] ?: 0) > 0 },
                product.status, product.slug.value, product.sku.value, product.details.name.value,
            ),
            listOf(
                row[ProductQmTbl.price], row[ProductQmTbl.compareAtPrice], row[ProductQmTbl.discountPercent],
                row[ProductQmTbl.basePrice], row[ProductQmTbl.baseCompareAtPrice],
                row[ProductQmTbl.colorKeys], row[ProductQmTbl.hasVideo], row[ProductQmTbl.inStock],
                row[ProductQmTbl.status], row[ProductQmTbl.slug], row[ProductQmTbl.sku], row[ProductQmTbl.name],
            ),
        )
        assertEquals(
            product.variants.sortedBy { it.id.value }.map {
                val priceTag = PricePolicy.priceTagOf(product, it)
                listOf(
                    it.id.value, it.colorKey.value, it.sku.value, priceTag.price.toman, priceTag.compareAtPrice?.toman,
                    it.ownPriceTag != null, it.isDefault, it.isActive, it.sortOrder, it.media,
                )
            },
            colorRows(product.id).map {
                listOf(
                    it[ProductVariantQmTbl.variantId], it[ProductVariantQmTbl.colorKey], it[ProductVariantQmTbl.sku],
                    it[ProductVariantQmTbl.price], it[ProductVariantQmTbl.compareAtPrice], it[ProductVariantQmTbl.hasOwnPrice],
                    it[ProductVariantQmTbl.isDefault], it[ProductVariantQmTbl.isActive], it[ProductVariantQmTbl.sortOrder],
                    it[ProductVariantQmTbl.media].map { media -> media.toDomain() },
                )
            },
        )
    }

    private fun restock(variantId: VariantId, bags: Int) = catalog.stock(variantId) {
        it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, staff, now)
    }

    /** Lilac on its own sale price (the default), black and cream following the product's 1,750,000. */
    private fun threeColors(): Product = catalog.newProduct(
        listOf(
            NewColor("lilac", ownPriceTag = priceTag(1_450_000, 1_750_000)),
            NewColor("black"),
            NewColor("cream"),
        ),
    )

    private fun Product.color(key: String): VariantId = variants.single { it.colorKey.value == key }.id

    // ============ step 5 is done when ============

    @Test
    fun `a product with three colors and stock in one of them is one row, ready for the filters`() {
        val product = threeColors()
        restock(product.color("black"), 4)

        val row = productRow(product.id)
        assertEquals(listOf("lilac", "black", "cream"), row[ProductQmTbl.colorKeys])
        assertEquals(true, row[ProductQmTbl.inStock])
        // The card shows the default color, lilac: round((1 − 1,450,000 / 1,750,000) × 100) = 17.
        assertEquals(listOf<Any?>(1_450_000L, 1_750_000L, 17), listOf(row[ProductQmTbl.price], row[ProductQmTbl.compareAtPrice], row[ProductQmTbl.discountPercent]))
        assertEquals(3, colorRows(product.id).size)
        assertEquals(listOf(4, 0), colorRow(product.color("black")).let { listOf(it[ProductVariantQmTbl.onHand], it[ProductVariantQmTbl.reserved]) })
        assertMatchesDomain(product, available = mapOf(product.color("black") to 4))
    }

    @Test
    fun `a price change updates that product's row and its colors, and nothing of any other product`() {
        val changed = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black", ownPriceTag = priceTag(1_200_000))))
        val other = threeColors()
        val otherBefore = productRow(other.id).allColumns()
        val otherColorsBefore = colorRows(other.id).map { it.allColumns() }

        val afterChange = catalog.change(changed.id) { it.changePrice(priceTag(1_890_000, 2_100_000), staff, now) }

        assertEquals(otherBefore, productRow(other.id).allColumns())
        assertEquals(otherColorsBefore, colorRows(other.id).map { it.allColumns() })
        // Lilac follows the product's price, black keeps its own; the card follows lilac, the default.
        assertEquals(listOf<Any?>(1_890_000L, 2_100_000L, 10), productRow(changed.id).let { listOf(it[ProductQmTbl.price], it[ProductQmTbl.compareAtPrice], it[ProductQmTbl.discountPercent]) })
        assertEquals(1_890_000L, colorRow(changed.color("lilac"))[ProductVariantQmTbl.price])
        assertEquals(1_200_000L, colorRow(changed.color("black"))[ProductVariantQmTbl.price])
        assertMatchesDomain(afterChange)
    }

    // ============ what each event changes ============

    @Test
    fun `a new product is a draft, out of stock, never published, with the product form's content`() {
        val product = threeColors()

        val row = productRow(product.id)
        val details = ProductEventSamples.fullDetails
        assertEquals(ProductStatus.DRAFT, row[ProductQmTbl.status])
        assertEquals(false, row[ProductQmTbl.inStock])
        assertNull(row[ProductQmTbl.publishedOn])
        assertEquals(listOf(now, now), listOf(row[ProductQmTbl.createdOn].toInstant(), row[ProductQmTbl.updatedOn].toInstant()))
        assertEquals(
            listOf(
                "shoulder", "faux-leather-matte", BagType.HOBO, details.newUntil, 10, listOf("DAILY", "WORK"), 3, false, false,
                details.shortDescription?.value, details.description?.value, details.highlights.items,
                details.materialDescription?.value, ProductSpecsPayload(details.specs),
            ),
            listOf(
                row[ProductQmTbl.categorySlug], row[ProductQmTbl.materialSlug], row[ProductQmTbl.bagType],
                row[ProductQmTbl.newUntil]?.toInstant(), row[ProductQmTbl.sortPriority], row[ProductQmTbl.occasions],
                row[ProductQmTbl.maxPerOrder], row[ProductQmTbl.returnable], row[ProductQmTbl.reservable], row[ProductQmTbl.shortDescription],
                row[ProductQmTbl.description], row[ProductQmTbl.highlights], row[ProductQmTbl.materialDescription],
                row[ProductQmTbl.specs],
            ),
        )
        assertEquals(listOf(0, 0), listOf(row[ProductQmTbl.soldCount], row[ProductQmTbl.ratingAvg].toInt()))
        assertMatchesDomain(product)
    }

    @Test
    fun `the search text holds name, category, active colors and material, written the Persian way`() {
        // Typed on an Arabic keyboard: ي and ك instead of ی and ک.
        val arabicName = ProductEventSamples.fullDetails.copy(name = ProductName.constructFromPersisted("كيف دوشي ماهك"))
        val product = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")), details = arabicName)

        assertEquals("کیف دوشی ماهک کیف دوشی یاسی مشکی چرم مصنوعی مات درجه یک", productRow(product.id)[ProductQmTbl.searchText])

        catalog.change(product.id) { it.deactivateVariant(product.color("black"), staff, now) }

        assertEquals("کیف دوشی ماهک کیف دوشی یاسی چرم مصنوعی مات درجه یک", productRow(product.id)[ProductQmTbl.searchText])
    }

    @Test
    fun `new details move the product to its new category and material, and its search text with them`() {
        val product = catalog.newProduct(listOf(NewColor("black")))
        val later = now.plusSeconds(60)

        val changed = catalog.change(product.id) { it.changeDetails(ProductEventSamples.bareDetails, staff, later) }

        val row = productRow(product.id)
        assertEquals(listOf("wallet", "genuine-cow-leather", BagType.WALLET), listOf(row[ProductQmTbl.categorySlug], row[ProductQmTbl.materialSlug], row[ProductQmTbl.bagType]))
        assertEquals("کیف پول ساده کیف پول مشکی چرم طبیعی گاوی", row[ProductQmTbl.searchText])
        assertEquals(
            listOf(null, null, emptyList<String>(), null, ProductSpecsPayload(ProductEventSamples.bareDetails.specs), emptyList<String>(), null, true, true, 5),
            listOf(
                row[ProductQmTbl.shortDescription], row[ProductQmTbl.description], row[ProductQmTbl.highlights],
                row[ProductQmTbl.materialDescription], row[ProductQmTbl.specs], row[ProductQmTbl.occasions],
                row[ProductQmTbl.newUntil], row[ProductQmTbl.returnable], row[ProductQmTbl.reservable], row[ProductQmTbl.maxPerOrder],
            ),
        )
        assertEquals(listOf(now, later), listOf(row[ProductQmTbl.createdOn].toInstant(), row[ProductQmTbl.updatedOn].toInstant()))
        assertMatchesDomain(changed)
    }

    @Test
    fun `making another color the default moves the card's price to it`() {
        val product = threeColors()
        val black = product.color("black")

        val changed = catalog.change(product.id) { it.changeVariant(black, priceTag(1_600_000), sortOrder = 1, makeDefault = true, staff, now) }

        assertEquals(listOf<Any?>(1_600_000L, null, 0), productRow(product.id).let { listOf(it[ProductQmTbl.price], it[ProductQmTbl.compareAtPrice], it[ProductQmTbl.discountPercent]) })
        assertEquals(listOf(false, true), listOf(colorRow(product.color("lilac")), colorRow(black)).map { it[ProductVariantQmTbl.isDefault] })
        assertMatchesDomain(changed)
    }

    @Test
    fun `a color that drops its own price follows the product's again, and moves when its position does`() {
        val product = threeColors()
        val lilac = product.color("lilac")

        val changed = catalog.change(product.id) { it.changeVariant(lilac, null, sortOrder = 5, makeDefault = false, staff, now) }

        val row = colorRow(lilac)
        assertEquals(listOf(1_750_000L, null, false), listOf(row[ProductVariantQmTbl.price], row[ProductVariantQmTbl.compareAtPrice], row[ProductVariantQmTbl.hasOwnPrice]))
        assertEquals(listOf("black", "cream", "lilac"), productRow(product.id)[ProductQmTbl.colorKeys])
        assertMatchesDomain(changed)
    }

    @Test
    fun `taking the default color out of the shop moves the default and drops its color, video and stock from the card`() {
        val product = catalog.newProduct(
            listOf(NewColor("lilac", media = listOf(ProductEventSamples.video)), NewColor("black", ownPriceTag = priceTag(1_200_000))),
        )
        val lilac = product.color("lilac")
        restock(lilac, 3)

        val deactivated = catalog.change(product.id) { it.deactivateVariant(lilac, staff, now) }

        val row = productRow(product.id)
        assertEquals(listOf(listOf("black"), false, false, 1_200_000L), listOf(row[ProductQmTbl.colorKeys], row[ProductQmTbl.hasVideo], row[ProductQmTbl.inStock], row[ProductQmTbl.price]))
        assertEquals(true, colorRow(product.color("black"))[ProductVariantQmTbl.isDefault])
        assertMatchesDomain(deactivated, available = mapOf(lilac to 3))

        val reactivated = catalog.change(product.id) { it.reactivateVariant(lilac, staff, now) }

        assertEquals(listOf(listOf("lilac", "black"), true, true), productRow(product.id).let { listOf(it[ProductQmTbl.colorKeys], it[ProductQmTbl.hasVideo], it[ProductQmTbl.inStock]) })
        assertMatchesDomain(reactivated, available = mapOf(lilac to 3))
    }

    @Test
    fun `a gallery with a video gives the product the video flag, and losing it takes the flag away`() {
        val product = threeColors()
        val cream = product.color("cream")

        val withVideo = catalog.change(product.id) { it.replaceVariantMedia(cream, listOf(ProductEventSamples.frontPhoto, ProductEventSamples.video), staff, now) }

        assertEquals(true, productRow(product.id)[ProductQmTbl.hasVideo])
        assertMatchesDomain(withVideo)

        val photosOnly = catalog.change(product.id) { it.replaceVariantMedia(cream, listOf(ProductEventSamples.frontPhoto), staff, now) }

        assertEquals(false, productRow(product.id)[ProductQmTbl.hasVideo])
        assertMatchesDomain(photosOnly)
    }

    @Test
    fun `publishing, unpublishing and archiving show in its status, and the last change in updatedOn`() {
        val product = threeColors()
        val publishedAt = now.plusSeconds(60)
        val unpublishedAt = now.plusSeconds(120)
        val archivedAt = now.plusSeconds(180)

        catalog.change(product.id) { it.publish(staff, publishedAt) }
        assertEquals(listOf(ProductStatus.ACTIVE, publishedAt), productRow(product.id).let { listOf(it[ProductQmTbl.status], it[ProductQmTbl.publishedOn]?.toInstant()) })

        catalog.change(product.id) { it.unpublish(staff, unpublishedAt) }
        // publishedOn keeps the last time it went into the shop.
        assertEquals(listOf(ProductStatus.DRAFT, publishedAt, unpublishedAt), productRow(product.id).let { listOf(it[ProductQmTbl.status], it[ProductQmTbl.publishedOn]?.toInstant(), it[ProductQmTbl.updatedOn].toInstant()) })

        val archived = catalog.change(product.id) { it.archive(staff, archivedAt) }
        assertEquals(listOf(ProductStatus.ARCHIVED, archivedAt), productRow(product.id).let { listOf(it[ProductQmTbl.status], it[ProductQmTbl.updatedOn].toInstant()) })
        assertMatchesDomain(archived)
    }

    @Test
    fun `stock changes don't count as a change of the product`() {
        val product = threeColors()

        restock(product.color("lilac"), 2)

        assertEquals(now, productRow(product.id)[ProductQmTbl.updatedOn].toInstant())
    }

    // ============ kept or rolled back with the events ============

    @Test
    fun `a save refused because someone saved first leaves nothing of its projection`() {
        val product = threeColors()
        val first = catalog.read(product.id)
        val second = catalog.read(product.id)
        first.changePrice(priceTag(1_890_000), staff, now).orFail()
        second.deactivateVariant(product.color("lilac"), staff, now).orFail()
        transaction(db) { ProductRepositoryImpl().save(first) }

        assertFailsWith<ConcurrentChangeException> { transaction(db) { ProductRepositoryImpl().save(second) } }

        assertEquals(listOf("lilac", "black", "cream"), productRow(product.id)[ProductQmTbl.colorKeys])
        assertMatchesDomain(first)
    }

    @Test
    fun `a slug another product has is refused by the read model, and the new product's events go with it`() {
        val first = catalog.newProduct(listOf(NewColor("lilac")), slug = "mahak-shoulder-bag")
        val eventsBefore = transaction(db) { EventStoreTbl.selectAll().count() }

        assertFailsWith<ExposedSQLException> { catalog.newProduct(listOf(NewColor("black")), slug = "mahak-shoulder-bag") }

        assertEquals(eventsBefore, transaction(db) { EventStoreTbl.selectAll().count() })
        assertEquals(
            listOf(first.id.value),
            transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.slug eq "mahak-shoulder-bag" }.map { it[ProductQmTbl.productId] } },
        )
    }

    @Test
    fun `a whole history replays into the same rows the domain describes`() {
        val product = threeColors()
        val (lilac, black, cream) = listOf("lilac", "black", "cream").map { product.color(it) }
        restock(black, 2)
        restock(cream, 6)

        // Six events in one save, each projected right after it is stored.
        val changed = catalog.change(product.id) {
            it.changePrice(priceTag(1_650_000, 1_900_000), staff, now).orFail()
            it.changeVariant(cream, priceTag(1_100_000, 1_300_000), sortOrder = 0, makeDefault = true, staff, now).orFail()
            it.replaceVariantMedia(black, listOf(ProductEventSamples.video), staff, now).orFail()
            it.deactivateVariant(lilac, staff, now).orFail()
            it.publish(staff, now).orFail()
            it.changeDetails(ProductEventSamples.fullDetails.copy(sortPriority = 3), staff, now)
        }

        assertMatchesDomain(changed, available = mapOf(black to 2, cream to 6))
        // And the product read back from its events is the one the rows describe.
        assertMatchesDomain(catalog.read(product.id), available = mapOf(black to 2, cream to 6))
    }
}
