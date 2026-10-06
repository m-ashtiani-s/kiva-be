package com.kiva.infrastructure.seed

import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The seeder on kiva_test with everything real: use cases, domain, event store and read model. Whatever it made is
 * deleted after each test, so every test starts from a catalog without products.
 */
class DevCatalogSeederTest {

    private val db = TestDatabase.database
    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val seeder = run {
        val transactions = ExposedTransactionManager(db)
        val products = ProductRepositoryImpl()
        val inventories = InventoryRepositoryImpl()
        val readModel = ProductQmRepositoryImpl()
        val productDomainService = ProductDomainService(
            products, ProductIdGeneratorImpl(), CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl(),
        )
        DevCatalogSeeder(
            CreateProductUc(transactions, productDomainService, products, readModel, clock),
            AdjustInventoryUc(transactions, InventoryDomainService(inventories), inventories, readModel, clock),
            ListCategoriesUc(transactions, CategoryRepositoryImpl(), readModel),
            ListMaterialsUc(transactions, MaterialRepositoryImpl(), readModel),
            clock,
        )
    }
    private val seededSlugs = DevCatalogData.bags.map { it.slug }

    @AfterEach
    fun deleteWhatWasSeeded() {
        transaction(db) {
            val productIds = ProductQmTbl.selectAll().where { ProductQmTbl.slug inList seededSlugs }.map { it[ProductQmTbl.productId] }
            val variantIds = ProductVariantQmTbl.selectAll()
                .where { ProductVariantQmTbl.productId inList productIds }
                .map { it[ProductVariantQmTbl.variantId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "PRODUCT") and (aggregateId inList productIds.map { it.toString() }) }
            EventStoreTbl.deleteWhere { (aggregateName eq "INVENTORY") and (aggregateId inList variantIds.map { it.toString() }) }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
        }
    }

    private fun productRow(slug: String): ResultRow =
        transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.slug eq slug }.single() }

    /** onHand of each color, in the prototype's color order. */
    private fun stockOf(slug: String): List<Int> = transaction(db) {
        val productId = productRow(slug)[ProductQmTbl.productId]
        ProductVariantQmTbl.selectAll()
            .where { ProductVariantQmTbl.productId eq productId }
            .orderBy(ProductVariantQmTbl.sortOrder to SortOrder.ASC)
            .map { it[ProductVariantQmTbl.onHand] }
    }

    private fun productCount(): Long = transaction(db) { ProductQmTbl.selectAll().count() }

    private fun eventCount(): Long = transaction(db) { EventStoreTbl.selectAll().count() }

    // ============ step 6 is done when ============

    @Test
    fun `a catalog without products gets the prototype's 16 bags, all in the shop`() = runTest {
        val report = seeder.seed()

        assertEquals(DevCatalogSeeder.Report(created = 16, skipped = 0), report)
        assertEquals(16, productCount())
        assertEquals(
            List(16) { ProductStatus.ACTIVE },
            transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.slug inList seededSlugs }.map { it[ProductQmTbl.status] } },
        )
    }

    @Test
    fun `seeding again makes nothing new, not even stock`() = runTest {
        seeder.seed()
        val eventsAfterFirstRun = eventCount()

        val report = seeder.seed()

        assertEquals(DevCatalogSeeder.Report(created = 0, skipped = 16), report)
        assertEquals(16, productCount())
        assertEquals(eventsAfterFirstRun, eventCount())
        assertEquals(List(4) { 12 }, stockOf("mahak-shoulder-bag"))
    }

    // ============ the bags read like the prototype ============

    @Test
    fun `each bag has the prototype's code, colors, price, stock and «new» mark`() = runTest {
        seeder.seed()

        val mahak = productRow("mahak-shoulder-bag")
        assertEquals(
            listOf<Any?>("KV-1001", listOf("black", "lilac", "cream", "brown"), 1_890_000L, null, 0, true, now.plus(DevCatalogSeeder.NEW_FOR)),
            listOf(
                mahak[ProductQmTbl.sku], mahak[ProductQmTbl.colorKeys], mahak[ProductQmTbl.price], mahak[ProductQmTbl.compareAtPrice],
                mahak[ProductQmTbl.discountPercent], mahak[ProductQmTbl.inStock], mahak[ProductQmTbl.newUntil]?.toInstant(),
            ),
        )
        assertEquals(
            listOf("shoulder", "faux-leather-matte", "چرم مصنوعی مات درجه یک", listOf("DAILY", "UNIVERSITY", "WORK")),
            listOf(mahak[ProductQmTbl.categorySlug], mahak[ProductQmTbl.materialSlug], mahak[ProductQmTbl.materialDescription], mahak[ProductQmTbl.occasions]),
        )
        assertEquals(List(4) { 12 }, stockOf("mahak-shoulder-bag"))

        // On sale: round((1 − 1,450,000 / 1,750,000) × 100) = 17, and not new.
        val nila = productRow("nila-crossbody-bag")
        assertEquals(listOf<Any?>(1_750_000L, 17, null), listOf(nila[ProductQmTbl.compareAtPrice], nila[ProductQmTbl.discountPercent], nila[ProductQmTbl.newUntil]))

        // Sold out in the prototype: in the shop, but with nothing in stock.
        assertEquals(false, productRow("shabtab-clutch")[ProductQmTbl.inStock])
        assertEquals(List(3) { 0 }, stockOf("shabtab-clutch"))
        assertEquals(List(4) { 3 }, stockOf("aida-handbag"))
    }

    @Test
    fun `every category gets its two bags, so the menus count real products now`() = runTest {
        seeder.seed()

        val categories = ListCategoriesUc(ExposedTransactionManager(db), CategoryRepositoryImpl(), ProductQmRepositoryImpl())
            .execute(ListCategoriesUc.Qry).getOrNull()!!

        assertEquals(List(8) { 2 }, categories.map { it.productCount })
    }

    @Test
    fun `the starting stock is a delivery recorded by the system, like any other change of stock`() = runTest {
        seeder.seed()

        val productId = productRow("mahak-shoulder-bag")[ProductQmTbl.productId]
        val stockEvents = transaction(db) {
            val variantIds = ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.productId eq productId }.map { it[ProductVariantQmTbl.variantId].toString() }
            EventStoreTbl.selectAll()
                .where { (EventStoreTbl.aggregateName eq "INVENTORY") and (EventStoreTbl.aggregateId inList variantIds) }
                .map { listOf(it[EventStoreTbl.eventType], it[EventStoreTbl.occurredByUserId]) }
        }

        assertEquals(List(4) { listOf("StockAdjusted", 0L) }, stockEvents)
        assertNull(productRow("aseman-tote-bag").let { row -> row[ProductQmTbl.newUntil] })
    }
}
