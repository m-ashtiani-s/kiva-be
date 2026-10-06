package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.DAMAGED
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.RESTOCK
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason.STOCKTAKE
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The stock columns of the read model on kiva_test, written by saving stock through the real repository, each step in
 * its own transaction as in the app.
 */
class InventoryQmProjectorTest {

    private val db = TestDatabase.database
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun order(code: String) = OrderCode.constructFromPersisted(code)
    private fun bags(n: Int) = Quantity.constructFromPersisted(n)
    private fun delta(n: Int) = StockDelta.constructFromPersisted(n)

    /** onHand and reserved of one color, as the read model has them. */
    private fun stockOf(variantId: VariantId): List<Int> = transaction(db) {
        ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.variantId eq variantId.value }.single()
            .let { listOf(it[ProductVariantQmTbl.onHand], it[ProductVariantQmTbl.reserved]) }
    }

    private fun inStock(product: Product): Boolean =
        transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.productId eq product.id.value }.single()[ProductQmTbl.inStock] }

    private fun Product.color(key: String): VariantId = variants.single { it.colorKey.value == key }.id

    @Test
    fun `every kind of stock event moves onHand and reserved of that color only, as the aggregate does`() {
        val product = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")))
        val lilac = product.color("lilac")

        catalog.stock(lilac) { it.adjust(delta(12), RESTOCK, null, staff, now) }
        assertEquals(listOf(12, 0), stockOf(lilac))

        catalog.stock(lilac) { it.reserve(order("KV-218340"), bags(2), UserId.SYSTEM, now) }
        catalog.stock(lilac) { it.reserve(order("KV-218341"), bags(3), UserId.SYSTEM, now) }
        assertEquals(listOf(12, 5), stockOf(lilac))

        catalog.stock(lilac) { it.ship(order("KV-218340"), staff, now) }
        assertEquals(listOf(10, 3), stockOf(lilac))

        catalog.stock(lilac) { it.releaseReservation(order("KV-218341"), UserId.SYSTEM, now) }
        catalog.stock(lilac) { it.adjust(delta(-1), DAMAGED, null, staff, now) }
        val last = catalog.stock(lilac) { it.adjust(delta(4), STOCKTAKE, null, staff, now) }

        assertEquals(listOf(last.onHand, last.reserved), stockOf(lilac))
        assertEquals(listOf(13, 0), stockOf(lilac))
        assertEquals(listOf(0, 0), stockOf(product.color("black")))
    }

    @Test
    fun `a product is in stock while one active color can still be sold`() {
        val product = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")))
        val lilac = product.color("lilac")
        assertEquals(false, inStock(product))

        catalog.stock(lilac) { it.adjust(delta(2), RESTOCK, null, staff, now) }
        assertEquals(true, inStock(product))

        // Both bags promised to an order: still on hand, but nothing left to sell.
        catalog.stock(lilac) { it.reserve(order("KV-218340"), bags(2), UserId.SYSTEM, now) }
        assertEquals(false, inStock(product))

        catalog.stock(lilac) { it.releaseReservation(order("KV-218340"), UserId.SYSTEM, now) }
        assertEquals(true, inStock(product))
    }

    @Test
    fun `stock of a color out of the shop doesn't keep its product in stock`() {
        val product = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")))
        val black = product.color("black")
        catalog.stock(black) { it.adjust(delta(5), RESTOCK, null, staff, now) }

        catalog.change(product.id) { it.deactivateVariant(black, staff, now) }

        assertEquals(false, inStock(product))
        assertEquals(listOf(5, 0), stockOf(black))
    }

    @Test
    fun `stock of another product's color leaves this product's row alone`() {
        val product = catalog.newProduct(listOf(NewColor("lilac")))
        val other = catalog.newProduct(listOf(NewColor("lilac")))

        catalog.stock(other.color("lilac")) { it.adjust(delta(3), RESTOCK, null, staff, now) }

        assertEquals(listOf(false, true), listOf(inStock(product), inStock(other)))
        assertEquals(listOf(0, 0), stockOf(product.color("lilac")))
    }

    @Test
    fun `stock of a color no product has is a bug - refused, and its event is not stored either`() {
        val nobodysColor = transaction(db) { ProductIdGeneratorImpl().nextVariantId() }
        val inventory = Inventory.empty(nobodysColor)
        inventory.adjust(delta(5), RESTOCK, null, staff, now).orFail()

        assertFailsWith<IllegalStateException> { transaction(db) { InventoryRepositoryImpl().save(inventory) } }

        assertEquals(0L, transaction(db) { EventStoreTbl.selectAll().where { EventStoreTbl.aggregateId eq nobodysColor.value.toString() }.count() })
    }
}
