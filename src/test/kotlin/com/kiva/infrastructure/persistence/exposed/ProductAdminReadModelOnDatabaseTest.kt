package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.AdminProductFilter
import com.kiva.application.repository.AdminProductQm
import com.kiva.application.usecase.product.usecase.query.AdminProductSorts
import com.kiva.common.pagination.Pageable
import com.kiva.common.text.normalizePersian
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the panel reads about products (step 1.4) on kiva_test: the columns V24 added, kept by the projectors and filled
 * by V24's own SQL the same way, and the panel's list and page. Every product is made through the real repositories
 * (StoredCatalog, StoredOrders) and removed after each test. Each test's products share a name word of their own, so
 * the list is narrowed to them with `q`.
 */
class ProductAdminReadModelOnDatabaseTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val catalog = orders.catalog
    private val readModel = ProductQmRepositoryImpl()

    @AfterEach
    fun deleteAll() = orders.deleteAll()

    private fun product(
        name: String,
        colors: List<String> = listOf("lilac"),
        status: ProductStatus = ProductStatus.ACTIVE,
        price: Long = 1_750_000,
        stock: List<Int> = emptyList(),
    ): Product {
        val details = ProductEventSamples.fullDetails.copy(name = ProductName.constructFromPersisted(name))
        var product = catalog.newProduct(colors.map { StoredCatalog.NewColor(it) }, details = details, priceTag = priceTag(price))
        stock.forEachIndexed { index, quantity ->
            if (quantity > 0) catalog.stock(product.variants[index].id) {
                it.adjust(StockDelta.constructFromPersisted(quantity), AdjustmentReason.RESTOCK, null, catalog.staff, catalog.now)
            }
        }
        product = when (status) {
            ProductStatus.ACTIVE -> catalog.change(product.id) { it.publish(catalog.staff, catalog.now) }
            ProductStatus.ARCHIVED -> catalog.change(product.id) { it.archive(catalog.staff, catalog.now) }
            ProductStatus.DRAFT -> product
        }
        return product
    }

    private fun page(filter: AdminProductFilter, sort: String = AdminProductSorts.DEFAULT, size: Int = 20): List<AdminProductQm> = transaction(db) {
        readModel.findForAdmin(filter, Pageable.makeNew(1, size, sort, AdminProductSorts.names).orFail()).items
    }

    private fun filter(q: String, status: ProductStatus? = null, stockStatus: StockStatus? = null) =
        AdminProductFilter(q = q.normalizePersian(), status = status, stockStatus = stockStatus, lowStockThreshold = StockPolicy.LOW_STOCK_THRESHOLD)

    private fun row(product: Product) = transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.productId eq product.id.value }.single() }

    // ============ the projectors ============

    @Test
    fun `the version is the number of the product's events, its maker is kept, and availableTotal sums its active colors`() {
        val bag = product("آلما", colors = listOf("lilac", "black", "cream"), stock = listOf(3, 5, 7))
        catalog.change(bag.id) { it.deactivateVariant(bag.variants[2].id, catalog.staff, catalog.now) }

        val stored = row(bag)

        // Created, three colors, published, one deactivated: six events. Stock changes are not the product's.
        assertEquals(6L, stored[ProductQmTbl.version])
        assertEquals(catalog.staff.value, stored[ProductQmTbl.createdByUserId])
        assertEquals(8, stored[ProductQmTbl.availableTotal])
        assertTrue(stored[ProductQmTbl.searchText].endsWith(" ${bag.sku.value}"), stored[ProductQmTbl.searchText])
    }

    @Test
    fun `a payment counts each color's own sales as well as the product's`() {
        val bag = orders.newBag()
        val order = orders.place(listOf(orders.line(1, bag, color = 0, quantity = 2), orders.line(2, bag, color = 1, quantity = 1)))

        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000C3"), it.totals.payable, false, UserId.SYSTEM, orders.now) }

        val colors = transaction(db) {
            ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.productId eq bag.id.value }.associate { it[ProductVariantQmTbl.variantId] to it[ProductVariantQmTbl.soldCount] }
        }
        assertEquals(mapOf(bag.variants[0].id.value to 2, bag.variants[1].id.value to 1), colors)
        assertEquals(3, orders.soldCountOf(bag))
    }

    @Test
    fun `V24's backfills give exactly what the projectors wrote`() {
        val bag = orders.newBag()
        val order = orders.place(listOf(orders.line(1, bag, color = 1, quantity = 2)))
        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000D4"), it.totals.payable, false, UserId.SYSTEM, orders.now) }
        catalog.stock(bag.variants[0].id) { it.adjust(StockDelta.constructFromPersisted(9), AdjustmentReason.RESTOCK, null, catalog.staff, catalog.now) }
        val projected = snapshotOf(bag)

        val backfilled = transaction(db) {
            // As the rows were before V24: its columns at their defaults, the search text without the code.
            ProductQmTbl.update({ ProductQmTbl.productId eq bag.id.value }) {
                it[version] = 1
                it[createdByUserId] = 0
                it[availableTotal] = 0
                it[searchText] = projected.searchText.removeSuffix(" ${bag.sku.value}")
            }
            ProductVariantQmTbl.update({ ProductVariantQmTbl.productId eq bag.id.value }) { it[soldCount] = 0 }
            backfillStatements().forEach { TransactionManager.current().exec(it) }
            snapshotOf(bag).also { rollback() }
        }

        assertEquals(projected, backfilled)
        assertEquals(mapOf(bag.variants[0].id.value to 0, bag.variants[1].id.value to 2), projected.soldByColor)
    }

    private data class Snapshot(val version: Long, val createdBy: Long, val availableTotal: Int, val searchText: String, val soldByColor: Map<Long, Int>)

    private fun snapshotOf(bag: Product): Snapshot = transaction(db) {
        val product = ProductQmTbl.selectAll().where { ProductQmTbl.productId eq bag.id.value }.single()
        val colors = ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.productId inList listOf(bag.id.value) }
            .associate { it[ProductVariantQmTbl.variantId] to it[ProductVariantQmTbl.soldCount] }
        Snapshot(product[ProductQmTbl.version], product[ProductQmTbl.createdByUserId], product[ProductQmTbl.availableTotal], product[ProductQmTbl.searchText], colors)
    }

    /** V24's UPDATE statements, as the file has them. */
    private fun backfillStatements(): List<String> =
        File("src/main/resources/db/migration/V24__product_admin_read_model.sql").readText()
            .split(';')
            .map { statement -> statement.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").trim() }
            .filter { it.startsWith("UPDATE") }
            .also { assertEquals(5, it.size, "V24's backfills: $it") }

    // ============ the panel's list ============

    @Test
    fun `the list has every status - and status narrows it to one`() {
        val active = product("بیتا", status = ProductStatus.ACTIVE)
        val draft = product("بیتا", status = ProductStatus.DRAFT)
        val archived = product("بیتا", status = ProductStatus.ARCHIVED)

        assertEquals(setOf(active.id, draft.id, archived.id), page(filter("بیتا")).map { it.productId }.toSet())
        assertEquals(listOf(draft.id), page(filter("بیتا", status = ProductStatus.DRAFT)).map { it.productId })
    }

    @Test
    fun `q finds a product by the start of its code, by a color's code, and by a color's exact barcode`() {
        val bag = product("کیانا", colors = listOf("lilac", "black"))
        product("کیانا")
        val black = bag.variants[1]
        transaction(db) { ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq black.id.value }) { it[barcode] = "6260000123456" } }

        assertEquals(listOf(bag.id), page(filter(bag.sku.value)).map { it.productId }.filter { it == bag.id })
        assertEquals(listOf(bag.id), page(filter("${bag.sku.value}-LILAC")).map { it.productId })
        assertEquals(listOf(bag.id), page(filter(bag.sku.value.lowercase() + "-bl")).map { it.productId })
        assertEquals(listOf(bag.id), page(filter("6260000123456")).map { it.productId })
        assertEquals(emptyList(), page(filter("626000012345")).map { it.productId })
    }

    @Test
    fun `stockStatus - a color with 3 and the others with 12 is LOW_STOCK, all over the threshold IN_STOCK, nothing left OUT_OF_STOCK`() {
        val low = product("ثمین", colors = listOf("lilac", "black", "cream"), stock = listOf(3, 12, 12))
        val plenty = product("ثمین", colors = listOf("lilac"), stock = listOf(12))
        val none = product("ثمین", colors = listOf("lilac"))

        assertEquals(listOf(low.id), page(filter("ثمین", stockStatus = StockStatus.LOW_STOCK)).map { it.productId })
        assertEquals(listOf(plenty.id), page(filter("ثمین", stockStatus = StockStatus.IN_STOCK)).map { it.productId })
        assertEquals(listOf(none.id), page(filter("ثمین", stockStatus = StockStatus.OUT_OF_STOCK)).map { it.productId })
        val lowRow = page(filter("ثمین", stockStatus = StockStatus.LOW_STOCK)).single()
        assertEquals(27, lowRow.availableTotal)
        assertEquals(3, lowRow.variantsCount)
        assertEquals(1, lowRow.lowStockVariants)
    }

    @Test
    fun `variantsCount counts inactive colors too - a deactivated color's stock is not in totalStock`() {
        val bag = product("مهرو", colors = listOf("lilac", "black"), stock = listOf(4, 6))
        catalog.change(bag.id) { it.deactivateVariant(bag.variants[1].id, catalog.staff, catalog.now) }

        val row = page(filter("مهرو")).single()

        assertEquals(2, row.variantsCount)
        assertEquals(4, row.availableTotal)
        assertEquals(1, row.lowStockVariants)
    }

    @Test
    fun `each sort puts the rows in its order, the larger id first on a tie`() {
        val cheap = product("ترنج", price = 900_000, stock = listOf(9))
        val dear = product("ترنج", price = 2_900_000, stock = listOf(1))
        val middle = product("ترنج", price = 1_500_000, stock = listOf(5))
        catalog.change(cheap.id) { it.changeDetails(it.details.copy(name = ProductName.constructFromPersisted("ترنج آ")), catalog.staff, catalog.now.plusSeconds(60)) }

        fun ids(sort: String) = page(filter("ترنج"), sort).map { it.productId }

        assertEquals(listOf(cheap.id, middle.id, dear.id), ids(AdminProductSorts.PRICE_ASC))
        assertEquals(listOf(dear.id, middle.id, cheap.id), ids(AdminProductSorts.PRICE_DESC))
        assertEquals(listOf(dear.id, middle.id, cheap.id), ids(AdminProductSorts.STOCK_ASC))
        // updatedOn: the renamed one last changed; the others tie on the moment and go larger id first.
        assertEquals(listOf(cheap.id, middle.id, dear.id), ids(AdminProductSorts.UPDATED))
        assertEquals(listOf(middle.id, dear.id, cheap.id), ids(AdminProductSorts.NEWEST))
        // «ترنج» twice (larger id first), then «ترنج آ».
        assertEquals(listOf(middle.id, dear.id, cheap.id), ids(AdminProductSorts.NAME))
        assertEquals(listOf(middle.id, dear.id, cheap.id), ids(AdminProductSorts.BESTSELLING))
    }

    @Test
    fun `category narrows the list to products of those categories`() {
        val bag = product("نیلا")

        assertEquals(listOf(bag.id), page(filter("نیلا").copy(categories = setOf(Slug.constructFromPersisted("shoulder")))).map { it.productId })
        assertEquals(emptyList(), page(filter("نیلا").copy(categories = setOf(Slug.constructFromPersisted("party")))).map { it.productId })
    }

    @Test
    fun `a page of 20 is read with the same few statements as a page of 1 - never one per product`() {
        repeat(20) { product("پرنیان", colors = listOf("lilac", "black")) }

        fun statementsFor(size: Int): Int = transaction(db) {
            val before = statementCount
            readModel.findForAdmin(filter("پرنیان"), Pageable.makeNew(1, size, AdminProductSorts.DEFAULT, AdminProductSorts.names).orFail())
            statementCount - before
        }

        // The rows, their total and the default colors' first media.
        assertEquals(3, statementsFor(20))
        assertEquals(3, statementsFor(1))
    }

    // ============ one product for the panel ============

    @Test
    fun `a draft without colors, an archived product and an inactive color are all read - nobody is null`() {
        val bare = catalog.newProduct(emptyList())
        val archived = product("گلاره", colors = listOf("lilac", "black"), status = ProductStatus.DRAFT)
        catalog.change(archived.id) { it.deactivateVariant(archived.variants[1].id, catalog.staff, catalog.now) }
        catalog.change(archived.id) { it.archive(catalog.staff, catalog.now) }

        val draft = transaction(db) { readModel.findAdminDetail(bare.id) }!!
        val gone = transaction(db) { readModel.findAdminDetail(archived.id) }!!

        assertEquals(ProductStatus.DRAFT, draft.status)
        assertEquals(emptyList(), draft.variants)
        assertEquals(emptyList(), draft.detail.variants)
        assertEquals(ProductStatus.ARCHIVED, gone.status)
        assertEquals(listOf(true, false), gone.variants.map { it.isActive })
        assertEquals(listOf(archived.variants[0].id), gone.detail.variants.map { it.variantId })
        assertNull(transaction(db) { readModel.findAdminDetail(ProductId.constructFromPersisted(987_654_321)) })
    }
}
