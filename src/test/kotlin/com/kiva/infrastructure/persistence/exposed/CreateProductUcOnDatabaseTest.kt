package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CreateProductUc with everything real, for what its own test can only pretend: two requests with the same slug or
 * code at the same moment. Both pass the use case's check; the database refuses the second, and that must still be
 * the use case's 409, not a 500.
 */
class CreateProductUcOnDatabaseTest {

    private val db = TestDatabase.database
    private val transactions = ExposedTransactionManager(db)
    private val products = ProductRepositoryImpl()

    private val slugs = listOf("race-bag", "race-bag-other")

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) {
            val productIds = ProductQmTbl.selectAll().where { ProductQmTbl.slug inList slugs }.map { it[ProductQmTbl.productId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "PRODUCT") and (aggregateId inList productIds.map { it.toString() }) }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
        }
    }

    private fun useCase(repository: ProductRepository = products) = CreateProductUc(
        transactions,
        ProductDomainService(repository, ProductIdGeneratorImpl(), CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl()),
        repository,
        ProductQmRepositoryImpl(),
        ProductInput(JsoupHtmlSanitizer),
        MediaAttachments(MediaRepositoryImpl()),
        InventoryDomainService(InventoryRepositoryImpl()),
        InventoryRepositoryImpl(),
        AuditTrailImpl(),
        Clock.systemUTC(),
    )

    /**
     * The real repository, except that just before the first save another request creates its own product and commits:
     * after this request's check, before its save. It runs on its own thread, so it has its own connection and
     * transaction, as a second HTTP request would.
     */
    private inner class AnotherRequestCreatesFirst(private val other: CreateProductUc.Cmd) : ProductRepository by products {
        var otherResult: Result<*, *>? = null

        override fun save(product: Product) {
            if (otherResult == null) thread { otherResult = runBlocking { useCase().execute(other) } }.join()
            products.save(product)
        }
    }

    private fun cmd(slug: String, sku: String? = null) = CreateProductUc.Cmd(
        fields = ProductInput.Fields(
            name = "کیف مسابقه",
            slug = slug,
            sku = sku,
            categoryId = transaction(db) { CategoryRepositoryImpl().findBySlug(Slug.constructFromPersisted("shoulder"))!!.id.value },
            bagType = "HOBO",
            materialId = transaction(db) { MaterialRepositoryImpl().findAll().first().id.value },
            materialDescription = null,
            price = 1_000_000,
            compareAtPrice = null,
            shortDescription = null,
            description = null,
            highlights = null,
            specs = null,
            tags = null,
            newUntil = null,
            maxPerOrder = null,
            returnable = null,
            reservable = null,
            sortPriority = null,
            seo = null,
        ),
        status = null,
        variants = listOf(ProductInput.Variant("lilac")),
        actor = null,
    )

    private fun countWithSlug(slug: String) = transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.slug eq slug }.count() }

    @Test
    fun `two requests with one slug at the same moment, the first makes it and the second is DUPLICATE_SLUG, nothing is a 500`() = runTest {
        val repository = AnotherRequestCreatesFirst(cmd("race-bag"))

        val second = useCase(repository).execute(cmd("race-bag"))

        assertTrue(repository.otherResult is Result.Success, "${repository.otherResult}")
        assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSlug(Slug.constructFromPersisted("race-bag"))), second)
        assertEquals(1, countWithSlug("race-bag"))
    }

    @Test
    fun `two requests with one typed code at the same moment, the second is DUPLICATE_SKU on that code`() = runTest {
        val repository = AnotherRequestCreatesFirst(cmd("race-bag-other", sku = "KV-RACE"))

        val second = useCase(repository).execute(cmd("race-bag", sku = "KV-RACE"))

        assertTrue(repository.otherResult is Result.Success, "${repository.otherResult}")
        assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSku("sku", "KV-RACE")), second)
        assertEquals(listOf(0L, 1L), listOf(countWithSlug("race-bag"), countWithSlug("race-bag-other")))
    }
}
