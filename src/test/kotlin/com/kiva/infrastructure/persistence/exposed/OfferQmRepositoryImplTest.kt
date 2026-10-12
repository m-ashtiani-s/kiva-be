package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.OfferQm
import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The offers the cart reads, on kiva_test, from products and stock saved through the real repositories. */
class OfferQmRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = OfferQmRepositoryImpl()
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun offers(vararg ids: Long): Map<VariantId, OfferQm> =
        transaction(db) { repository.findOffers(ids.map { VariantId.constructFromPersisted(it) }) }

    private fun Product.color(key: String): VariantId = variants.single { it.colorKey.value == key }.id

    private fun Product.colorId(key: String): Long = color(key).value

    /** A published bag in lilac and black, the black one with its own sale price, and some of each in stock. */
    private fun inShop(): Product {
        val product = catalog.newProduct(
            listOf(NewColor("lilac"), NewColor("black", ownPriceTag = priceTag(1_450_000, compareAt = 1_750_000))),
            priceTag(1_890_000),
        )
        catalog.stock(product.color("lilac")) { it.adjust(StockDelta.constructFromPersisted(7), AdjustmentReason.RESTOCK, null, staff, now) }
        return catalog.change(product.id) { it.publish(staff, now) }
    }

    @Test
    fun `a color of a bag in the shop - its price, what is left, the product's limit, its names`() {
        val product = inShop()

        val lilac = offers(product.colorId("lilac")).getValue(product.color("lilac"))

        assertEquals(product.id, lilac.offer.productId)
        assertEquals(priceTag(1_890_000), lilac.offer.priceTag)
        assertEquals(7, lilac.offer.available)
        assertEquals(3, lilac.offer.maxPerOrder.value) // the sample details' limit
        assertTrue(lilac.offer.inShop)
        assertTrue(lilac.offer.isPurchasable)
        assertFalse(lilac.offer.reservable)
        assertEquals("کیف دوشی ماهک", lilac.product.name)
        assertEquals(OfferQm.Category(1, "shoulder", "کیف دوشی"), lilac.category)
        assertEquals("lilac", lilac.color.key)
        assertEquals(product.variants.single { it.colorKey.value == "lilac" }.sku.value, lilac.color.sku)
        assertEquals(1L, lilac.offer.categoryId.value)
    }

    @Test
    fun `a color's own price tag, and none left`() {
        val product = inShop()

        val black = offers(product.colorId("black")).getValue(product.color("black"))

        assertEquals(priceTag(1_450_000, compareAt = 1_750_000), black.offer.priceTag)
        assertEquals(0, black.offer.available)
        assertTrue(black.offer.inShop)
        assertFalse(black.offer.isPurchasable)
    }

    @Test
    fun `what orders hold is not available`() {
        val product = inShop()
        catalog.stock(product.color("lilac")) { it.reserve(OrderCode.constructFromPersisted("KV-100001"), Quantity.constructFromPersisted(5), staff, now) }

        assertEquals(2, offers(product.colorId("lilac")).getValue(product.color("lilac")).offer.available)
    }

    @Test
    fun `a draft, an archived product or a switched-off color is there, but not in the shop`() {
        val draft = catalog.newProduct(listOf(NewColor("lilac")))
        val archived = inShop().also { catalog.change(it.id) { product -> product.archive(staff, now) } }
        val switchedOff = inShop().also { catalog.change(it.id) { product -> product.deactivateVariant(it.color("black"), staff, now) } }

        val found = offers(draft.colorId("lilac"), archived.colorId("lilac"), switchedOff.colorId("black"), switchedOff.colorId("lilac"))

        assertEquals(4, found.size)
        assertFalse(found.getValue(draft.color("lilac")).offer.inShop)
        assertFalse(found.getValue(archived.color("lilac")).offer.inShop)
        assertFalse(found.getValue(switchedOff.color("black")).offer.inShop)
        assertTrue(found.getValue(switchedOff.color("lilac")).offer.inShop)
    }

    @Test
    fun `a color nobody has is simply missing, and asking for none reads nothing`() {
        assertEquals(emptyMap(), offers(987_654_321))
        assertEquals(emptyMap(), offers())
    }
}
