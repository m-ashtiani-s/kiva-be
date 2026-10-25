package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductRef
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One product's page from the read model on kiva_test, over products saved through the real repositories. */
class ProductQmRepositoryDetailTest {

    private val db = TestDatabase.database
    private val repository = ProductQmRepositoryImpl()
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun detail(ref: ProductRef): ProductDetailQm? = transaction(db) { repository.findDetail(ref) }

    private fun inShop(colors: List<NewColor>, slug: String = "mahak-shoulder-bag"): Product {
        val product = catalog.newProduct(colors, priceTag(1_890_000), slug = slug)
        return catalog.change(product.id) { it.publish(staff, now) }
    }

    private fun Product.color(key: String): VariantId = variants.single { it.colorKey.value == key }.id

    @Test
    fun `the same product by its number, its code and its slug`() {
        val product = inShop(listOf(NewColor("lilac")))

        val byId = detail(ProductRef.ById(product.id))
        val bySku = detail(ProductRef.BySku(product.sku))
        val bySlug = detail(ProductRef.BySlug(Slug.constructFromPersisted("mahak-shoulder-bag")))

        assertEquals(product.id, byId?.card?.productId)
        assertEquals(listOf(byId, byId), listOf(bySku, bySlug))
    }

    @Test
    fun `a draft, an archived product and a number nobody has have no page`() {
        val draft = catalog.newProduct(listOf(NewColor("lilac")), slug = "a-draft")
        val archived = inShop(listOf(NewColor("lilac")), slug = "an-archived-one")
        catalog.change(archived.id) { it.archive(staff, now) }

        assertNull(detail(ProductRef.ById(draft.id)))
        assertNull(detail(ProductRef.BySlug(Slug.constructFromPersisted("a-draft"))))
        assertNull(detail(ProductRef.ById(archived.id)))
        assertNull(detail(ProductRef.ById(ProductId.constructFromPersisted(999_999))))
    }

    @Test
    fun `a page has the active colors in display order, each with its code, price, stock and gallery, and the product's texts`() {
        val product = inShop(
            listOf(
                NewColor("lilac", ownPriceTag = priceTag(1_450_000, 1_750_000), media = listOf(ProductEventSamples.frontPhoto, ProductEventSamples.video)),
                NewColor("black"),
                NewColor("cream"),
            ),
        )
        catalog.change(product.id) { it.deactivateVariant(product.color("cream"), staff, now) }
        catalog.stock(product.color("black")) { it.adjust(StockDelta.constructFromPersisted(2), AdjustmentReason.RESTOCK, null, staff, now) }

        val page = detail(ProductRef.ById(product.id))!!

        assertEquals(
            listOf(
                ProductDetailQm.Variant(
                    product.color("lilac"), ColorKey.constructFromPersisted("lilac"), product.variants[0].sku, priceTag(1_450_000, 1_750_000),
                    isDefault = true, available = 0, media = listOf(ProductEventSamples.frontPhoto, ProductEventSamples.video).map(SampleMedia::dtoOf),
                ),
                ProductDetailQm.Variant(
                    product.color("black"), ColorKey.constructFromPersisted("black"), product.variants[1].sku, priceTag(1_890_000),
                    isDefault = false, available = 2, media = emptyList(),
                ),
            ),
            page.variants,
        )
        assertEquals(listOf("lilac", "black"), page.card.colors.map { it.colorKey.value })
        val details = ProductEventSamples.fullDetails
        assertEquals(
            listOf<Any?>(
                "faux-leather-matte", details.materialDescription, details.shortDescription, details.description, details.highlights,
                details.specs, details.returnable, details.reservable, now, now,
            ),
            listOf(
                page.materialSlug.value, page.materialDescription, page.shortDescription, page.description, page.highlights,
                page.specs, page.returnable, page.reservable, page.publishedOn, page.updatedOn,
            ),
        )
    }
}
