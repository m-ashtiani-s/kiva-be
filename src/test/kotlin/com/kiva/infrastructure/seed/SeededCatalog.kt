package com.kiva.infrastructure.seed

import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.InventoryAdjustmentQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

/**
 * The dev seeder on kiva_test with everything real: use cases, domain, event store and read model. [deleteSeeded]
 * removes whatever it made, events and read model rows, and belongs in an @AfterEach.
 */
class SeededCatalog(clock: Clock) {

    private val db = TestDatabase.database

    private val transactions = ExposedTransactionManager(db)
    private val readModel = ProductQmRepositoryImpl()

    /** The use case the seeder makes its bags with, for a test that wants one more product made the same way. */
    val createProduct: CreateProductUc = run {
        val products = ProductRepositoryImpl()
        val inventories = InventoryRepositoryImpl()
        val productDomainService = ProductDomainService(
            products, ProductIdGeneratorImpl(), CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl(),
        )
        CreateProductUc(
            transactions, productDomainService, products, readModel, ProductInput(JsoupHtmlSanitizer), MediaAttachments(MediaRepositoryImpl()),
            InventoryDomainService(inventories), inventories, AuditTrailImpl(), clock,
        )
    }

    val seeder: DevCatalogSeeder = DevCatalogSeeder(
        createProduct,
        ListCategoriesUc(transactions, CategoryRepositoryImpl(), readModel, MediaRepositoryImpl()),
        ListMaterialsUc(transactions, MaterialRepositoryImpl(), readModel),
        clock,
    )

    val slugs: List<String> = DevCatalogData.bags.map { it.slug }

    fun deleteSeeded() = deleteProducts(slugs)

    /** The products of [slugs], events and read model rows, as [deleteSeeded] removes the seeded ones. */
    fun deleteProducts(slugs: List<String>) {
        transaction(db) {
            val productIds = ProductQmTbl.selectAll().where { ProductQmTbl.slug inList slugs }.map { it[ProductQmTbl.productId] }
            val variantIds = ProductVariantQmTbl.selectAll()
                .where { ProductVariantQmTbl.productId inList productIds }
                .map { it[ProductVariantQmTbl.variantId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "PRODUCT") and (aggregateId inList productIds.map { it.toString() }) }
            EventStoreTbl.deleteWhere { (aggregateName eq "INVENTORY") and (aggregateId inList variantIds.map { it.toString() }) }
            InventoryAdjustmentQmTbl.deleteWhere { InventoryAdjustmentQmTbl.variantId inList variantIds }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
        }
    }
}
