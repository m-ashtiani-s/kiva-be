package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The filter counts and the related products on kiva_test, over products saved through the real repositories. */
class ProductQmRepositoryFacetsTest {

    private val db = TestDatabase.database
    private val repository = ProductQmRepositoryImpl()
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now
    private val today = Instant.parse("2026-10-07T08:00:00Z")

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun slug(value: String) = Slug.constructFromPersisted(value)
    private fun color(key: String) = ColorKey.constructFromPersisted(key)
    private fun money(toman: Long) = Money.constructFromPersisted(toman)

    private fun inShop(
        slug: String,
        colors: List<NewColor>,
        price: Long,
        compareAt: Long? = null,
        details: ProductDetails = ProductEventSamples.fullDetails,
        stock: Int = 0,
    ): Product {
        val product = catalog.newProduct(colors, priceTag(price, compareAt), details, slug)
        if (stock > 0) {
            catalog.stock(product.variants.first().id) { it.adjust(StockDelta.constructFromPersisted(stock), AdjustmentReason.RESTOCK, null, staff, now) }
        }
        return catalog.change(product.id) { it.publish(staff, now) }
    }

    private fun facets(filter: ProductFilter = ProductFilter()): ProductFacetsQm = transaction(db) { repository.facets(filter, today) }

    /**
     * In the shop: a shoulder bag in lilac and black, 3 lilac in stock, new until 2026-10-20; a cream wallet on sale
     * with a video, sold out. Not in the shop: a draft shoulder bag in black.
     */
    private fun twoInTheShop() {
        inShop("mahak-shoulder-bag", listOf(NewColor("lilac"), NewColor("black")), price = 1_750_000, stock = 3)
        inShop(
            "simple-wallet", listOf(NewColor("cream", media = listOf(ProductEventSamples.video))),
            price = 590_000, compareAt = 790_000, details = ProductEventSamples.bareDetails,
        )
        catalog.newProduct(listOf(NewColor("black")), priceTag(2_000_000), slug = "a-draft")
    }

    // ============ facets ============

    @Test
    fun `without filters every count is over the products in the shop`() {
        twoInTheShop()

        assertEquals(
            ProductFacetsQm(
                categories = mapOf(slug("shoulder") to 1, slug("wallet") to 1),
                colors = mapOf(color("lilac") to 1, color("black") to 1, color("cream") to 1),
                materials = mapOf(slug("faux-leather-matte") to 1, slug("genuine-cow-leather") to 1),
                bagTypes = mapOf(BagType.HOBO to 1, BagType.WALLET to 1),
                minPrice = money(590_000),
                maxPrice = money(1_750_000),
                onSale = 1,
                inStock = 1,
                new = 1,
                withVideo = 1,
            ),
            facets(),
        )
    }

    @Test
    fun `a picked color narrows every other count, but not the colors' own`() {
        twoInTheShop()

        val lilac = facets(ProductFilter(colors = setOf(color("lilac"))))

        assertEquals(mapOf(color("lilac") to 1, color("black") to 1, color("cream") to 1), lilac.colors)
        assertEquals(mapOf(slug("shoulder") to 1), lilac.categories)
        assertEquals(mapOf(BagType.HOBO to 1), lilac.bagTypes)
        assertEquals(listOf(money(1_750_000), money(1_750_000)), listOf(lilac.minPrice, lilac.maxPrice))
        assertEquals(listOf(0, 1, 1, 0), listOf(lilac.onSale, lilac.inStock, lilac.new, lilac.withVideo))
    }

    @Test
    fun `each switch is counted without itself, with the other filters`() {
        twoInTheShop()

        val onSaleWallets = facets(ProductFilter(categories = setOf(slug("wallet")), onSale = true))

        assertEquals(mapOf(slug("wallet") to 1), onSaleWallets.categories)
        assertEquals(1, onSaleWallets.onSale)
        assertEquals(mapOf(color("cream") to 1), onSaleWallets.colors)
        // The price range ignores the price filter, so the slider can be widened again.
        val narrowed = facets(ProductFilter(price = PriceRange.makeNew(money(600_000), null).getOrNull()!!))
        assertEquals(listOf(money(590_000), money(1_750_000)), listOf(narrowed.minPrice, narrowed.maxPrice))
        assertEquals(mapOf(slug("shoulder") to 1), narrowed.categories)
    }

    @Test
    fun `when nothing matches there are no counts and no price range`() {
        twoInTheShop()

        val nothing = facets(ProductFilter(words = listOf("هیچ‌چیز")))

        assertEquals(listOf(emptyMap<Any, Int>(), emptyMap()), listOf(nothing.categories, nothing.colors))
        assertEquals(listOf(null, null), listOf(nothing.minPrice, nothing.maxPrice))
    }

    // ============ related products ============

    @Test
    fun `related products are those of the same category first, then the rest, what can be bought first, never the product itself`() {
        val mahak = inShop("mahak-shoulder-bag", listOf(NewColor("lilac")), price = 1_750_000, stock = 2)
        inShop("hasti-shoulder-bag", listOf(NewColor("cream")), price = 2_150_000)
        inShop("sold-out-wallet", listOf(NewColor("black")), price = 590_000, details = ProductEventSamples.bareDetails)
        inShop("wallet-in-stock", listOf(NewColor("black")), price = 690_000, details = ProductEventSamples.bareDetails, stock = 4)
        catalog.newProduct(listOf(NewColor("lilac")), priceTag(1_000_000), slug = "a-draft")

        val related = transaction(db) { repository.findRelated(mahak.id, limit = 8) }!!
        val firstTwo = transaction(db) { repository.findRelated(mahak.id, limit = 2) }!!

        assertEquals(listOf("hasti-shoulder-bag", "wallet-in-stock", "sold-out-wallet"), related.map { it.slug.value })
        assertEquals(listOf("hasti-shoulder-bag", "wallet-in-stock"), firstTwo.map { it.slug.value })
        // Cards with their colors, like the list's.
        assertEquals(listOf(color("black")), related[1].colors.map { it.colorKey })
    }

    @Test
    fun `a product not in the shop has no related products`() {
        val draft = catalog.newProduct(listOf(NewColor("lilac")), priceTag(1_000_000), slug = "a-draft")

        assertNull(transaction(db) { repository.findRelated(draft.id, limit = 8) })
        assertNull(transaction(db) { repository.findRelated(ProductId.constructFromPersisted(999_999), limit = 8) })
    }
}
