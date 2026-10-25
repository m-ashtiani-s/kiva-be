package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.UpdateProductUc
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two saves of the product form from the same version at the same moment (step 1.5): the event store keeps one and
 * refuses the other with ConcurrentChangeException (the HTTP layer's 409 CHANGED_BY_SOMEONE_ELSE), and nothing of the
 * refused one is stored. Each save runs on its own thread, with its own connection and transaction, as two requests do.
 */
class UpdateProductUcOnDatabaseTest {

    private val db = TestDatabase.database
    private val catalog = StoredCatalog()
    private val products = ProductRepositoryImpl()

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    /** The real repository, except that each read waits until the other request has read too: both forms see one version. */
    private class BothReadFirst(private val products: ProductRepository, private val barrier: CyclicBarrier) : ProductRepository by products {
        override fun get(id: ProductId): Product? = products.get(id).also { barrier.await(10, TimeUnit.SECONDS) }
    }

    private fun useCase(repository: ProductRepository) = UpdateProductUc(
        ExposedTransactionManager(db),
        ProductDomainService(repository, ProductIdGeneratorImpl(), CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl()),
        repository,
        ProductInput(JsoupHtmlSanitizer),
        AuditTrailImpl(),
        Clock.systemUTC(),
    )

    private fun form(product: Product, name: String) = ProductInput.Fields(
        name = name, slug = product.slug.value, sku = null, categoryId = product.details.categoryId.value, bagType = product.details.bagType.name,
        materialId = product.details.materialId.value, materialDescription = null, price = 1_750_000, compareAtPrice = null, shortDescription = null,
        description = null, highlights = null, specs = null, tags = null, newUntil = null, maxPerOrder = null, returnable = null, reservable = null,
        sortPriority = null, seo = null,
    )

    @Test
    fun `two saves of one version at the same moment, 10 times - always one saved and one refused, and only the winner's change stored`() {
        repeat(10) { round ->
            val product = catalog.newProduct(listOf(NewColor("lilac")))
            val barrier = CyclicBarrier(2)
            val useCase = useCase(BothReadFirst(products, barrier))
            val etag = "W/\"${product.version}\""
            val outcomes = arrayOfNulls<Any>(2)

            val threads = listOf("کیف اول", "کیف دوم").mapIndexed { i, name ->
                thread {
                    outcomes[i] = try {
                        runBlocking { useCase.execute(UpdateProductUc.Cmd(product.id.value.toString(), form(product, name), etag, actor)) }
                    } catch (e: ConcurrentChangeException) {
                        e
                    }
                }
            }
            threads.forEach { it.join() }

            val saved = outcomes.indexOfFirst { it is Result.Success<*> }
            assertTrue(saved >= 0, "round $round: ${outcomes.toList()}")
            assertTrue(outcomes[1 - saved] is ConcurrentChangeException, "round $round: ${outcomes.toList()}")
            val stored = catalog.read(product.id)
            assertEquals(product.version + 1, stored.version, "round $round: one ProductDetailsChanged, the price being the same")
            assertEquals(listOf("کیف اول", "کیف دوم")[saved], stored.details.name.value, "round $round")
        }
    }

    private val actor = PanelActor(catalog.staff, null)
}
