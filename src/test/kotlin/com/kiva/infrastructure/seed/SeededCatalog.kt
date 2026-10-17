package com.kiva.infrastructure.seed

import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
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

    val seeder: DevCatalogSeeder = run {
        val transactions = ExposedTransactionManager(db)
        val products = ProductRepositoryImpl()
        val inventories = InventoryRepositoryImpl()
        val readModel = ProductQmRepositoryImpl()
        val productDomainService = ProductDomainService(
            products, ProductIdGeneratorImpl(), CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl(),
        )
        DevCatalogSeeder(
            CreateProductUc(transactions, productDomainService, products, readModel, JsoupHtmlSanitizer, clock),
            AdjustInventoryUc(transactions, InventoryDomainService(inventories), inventories, readModel, clock),
            ListCategoriesUc(transactions, CategoryRepositoryImpl(), readModel, MediaRepositoryImpl()),
            ListMaterialsUc(transactions, MaterialRepositoryImpl(), readModel),
            clock,
        )
    }

    val slugs: List<String> = DevCatalogData.bags.map { it.slug }

    fun deleteSeeded() {
        transaction(db) {
            val productIds = ProductQmTbl.selectAll().where { ProductQmTbl.slug inList slugs }.map { it[ProductQmTbl.productId] }
            val variantIds = ProductVariantQmTbl.selectAll()
                .where { ProductVariantQmTbl.productId inList productIds }
                .map { it[ProductVariantQmTbl.variantId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "PRODUCT") and (aggregateId inList productIds.map { it.toString() }) }
            EventStoreTbl.deleteWhere { (aggregateName eq "INVENTORY") and (aggregateId inList variantIds.map { it.toString() }) }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
        }
    }
}
