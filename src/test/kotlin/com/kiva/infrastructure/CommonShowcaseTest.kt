package com.kiva.infrastructure

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Money
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/**
 * A tiny but complete "add product / list products" slice that uses every piece of common together,
 * against the real `kiva_test` database. It is the reference for how a real feature is put together.
 *
 *   HTTP route -> use case -> value objects -> transaction -> repository (Exposed) -> HTTP response
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommonShowcaseTest {

    // ============ domain ============

    @JvmInline
    value class ProductName private constructor(val value: String) {
        companion object {
            fun of(raw: String): Result<ProductName, Failure> {
                val trimmed = raw.trim()
                return when {
                    trimmed.isEmpty() -> Result.failure(Failure.Blank)
                    trimmed.length > 100 -> Result.failure(Failure.TooLong)
                    else -> Result.success(ProductName(trimmed))
                }
            }
        }

        sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "name") {
            data object Blank : Failure("PRODUCT_NAME_BLANK")
            data object TooLong : Failure("PRODUCT_NAME_TOO_LONG")
        }
    }

    data class Product(val id: Long, val name: ProductName, val price: Money)

    interface ProductRepository {
        fun nameExists(name: ProductName): Boolean
        fun add(name: ProductName, price: Money): Long
        fun page(pageable: Pageable): Page<Product>
    }

    // ============ application ============

    data class AddProductCmd(val name: String, val priceRials: Long)

    sealed class AddProductFailure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, field, causes) {
        data class InvalidProduct(val reasons: List<ResultFailure>) : AddProductFailure("INVALID_PRODUCT", FailureKind.INVALID, causes = reasons)
        data object NameTaken : AddProductFailure("PRODUCT_NAME_TAKEN", FailureKind.CONFLICT, field = "name")
    }

    class AddProductUc(
        private val tx: TransactionManager,
        private val products: ProductRepository,
    ) : CommandUseCase<AddProductCmd, Long, AddProductFailure> {
        override suspend fun execute(command: AddProductCmd) = tx.inTransaction<Long, AddProductFailure> {
            // 1) Check every input at once (zip), so the form can show all mistakes together.
            val (name, price) = Result.zip(
                ProductName.of(command.name),
                Money.ofRials(command.priceRials).forField("price"),
                AddProductFailure::InvalidProduct,
            ) { n, p -> n to p }
                .unwrapOr { return@inTransaction Result.failure(it) }

            // 2) A rule that needs the database.
            if (products.nameExists(name)) return@inTransaction Result.failure(AddProductFailure.NameTaken)

            // 3) Write and return only the new id.
            Result.success(products.add(name, price))
        }
    }

    /** Listing can't fail once the Pageable is valid, so its failure type is Nothing. */
    class ListProductsUc(
        private val tx: TransactionManager,
        private val products: ProductRepository,
    ) : QueryUseCase<Pageable, Page<Product>, Nothing> {
        override suspend fun execute(query: Pageable) = tx.inTransaction<Page<Product>, Nothing> {
            Result.success(products.page(query))
        }
    }

    // ============ infrastructure ============

    object ProductsTbl : Table("showcase_products") {
        val id = long("id").autoIncrement()
        val name = varchar("name", 100).uniqueIndex()
        val price = long("price_rials")
        override val primaryKey = PrimaryKey(id)
    }

    class ExposedProductRepository : ProductRepository {
        companion object {
            val sortColumns = mapOf("name" to ProductsTbl.name, "price" to ProductsTbl.price)
        }

        override fun nameExists(name: ProductName) = ProductsTbl.selectAll().where { ProductsTbl.name eq name.value }.count() > 0

        override fun add(name: ProductName, price: Money): Long = ProductsTbl.insert {
            it[ProductsTbl.name] = name.value
            it[ProductsTbl.price] = price.rials
        }[ProductsTbl.id]

        override fun page(pageable: Pageable): Page<Product> =
            ProductsTbl.selectAll().paginate(pageable, sortColumns, tiebreaker = ProductsTbl.id) {
                Product(
                    id = it[ProductsTbl.id],
                    name = ProductName.of(it[ProductsTbl.name]).getOrNull()!!,
                    price = Money.ofRials(it[ProductsTbl.price]).getOrNull()!!,
                )
            }
    }

    @Serializable
    data class AddProductHttpRequest(val name: String, val price: Long)

    @Serializable
    data class ProductHttpResponse(val id: Long, val name: String, val price: Long)

    @Serializable
    data class CreatedHttpResponse(val id: Long)

    // ============ wiring + routes ============

    private val db = TestDatabase.database
    private val tx = ExposedTransactionManager(db)
    private val repository = ExposedProductRepository()
    private val addProduct = AddProductUc(tx, repository)
    private val listProducts = ListProductsUc(tx, repository)

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                post("/products") {
                    val request = call.receive<AddProductHttpRequest>()
                    val result = addProduct.execute(AddProductCmd(request.name, request.price))
                    call.respondResult(result, HttpStatusCode.Created) { CreatedHttpResponse(it) }
                }
                get("/products") {
                    val pageable = call.pageable(allowedSorts = ExposedProductRepository.sortColumns.keys)
                        .unwrapOr { return@get call.respondFailure(it) }
                    call.respondResult(listProducts.execute(pageable)) { page ->
                        page.toHttpResponse { ProductHttpResponse(it.id, it.name.value, it.price.rials) }
                    }
                }
            }
        }
        block()
    }

    @BeforeAll
    fun createTable() = transaction(db) { SchemaUtils.create(ProductsTbl) }

    @AfterAll
    fun dropTable() = transaction(db) { SchemaUtils.drop(ProductsTbl) }

    @BeforeEach
    fun clearTable() {
        transaction(db) { ProductsTbl.deleteAll() }
    }

    private suspend fun ApplicationTestBuilder.addProduct(body: String): HttpResponse = client.post("/products") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun JsonElement.itemNames(): List<String> =
        jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    private fun storedNames(): List<String> = transaction(db) { ProductsTbl.selectAll().map { it[ProductsTbl.name] } }

    @Test
    fun `a valid product is created (201) and appears in the list`() = withApp {
        val created = addProduct("""{"name":"  Mug  ","price":250000}""")
        assertEquals(HttpStatusCode.Created, created.status)

        val id = created.json().jsonObject.getValue("id").jsonPrimitive.long
        val list = client.get("/products").json()
        assertEquals(
            json("""{"items":[{"id":$id,"name":"Mug","price":250000}],"page":1,"size":20,"totalItems":1,"totalPages":1}"""),
            list,
        )
    }

    @Test
    fun `every invalid field is reported at once (400) and nothing is written`() = withApp {
        val response = addProduct("""{"name":"   ","price":-5}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            json(
                """{"code":"INVALID_PRODUCT","causes":[
                     {"code":"PRODUCT_NAME_BLANK","field":"name"},
                     {"code":"MONEY_NEGATIVE","field":"price"}]}""",
            ),
            response.json(),
        )
        assertEquals(emptyList(), storedNames())
    }

    @Test
    fun `a duplicate name is a conflict (409)`() = withApp {
        addProduct("""{"name":"Mug","price":1000}""")
        val response = addProduct("""{"name":"Mug","price":2000}""")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(json("""{"code":"PRODUCT_NAME_TAKEN","field":"name"}"""), response.json())
        assertEquals(listOf("Mug"), storedNames())
    }

    @Test
    fun `the list is paginated and sorted`() = withApp {
        listOf("A" to 300, "B" to 100, "C" to 200).forEach { (name, price) -> addProduct("""{"name":"$name","price":$price}""") }

        val page1 = client.get("/products?size=2&sort=-price").json()
        val page2 = client.get("/products?page=2&size=2&sort=-price").json()

        assertEquals(listOf("A", "C"), page1.itemNames()) // most expensive first
        assertEquals(listOf("B"), page2.itemNames())
        assertEquals(3L, page2.jsonObject.getValue("totalItems").jsonPrimitive.long)
    }

    @Test
    fun `a bad list request is a 400 with every problem`() = withApp {
        val response = client.get("/products?page=0&sort=password")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            json(
                """{"code":"INVALID_PAGINATION","causes":[
                     {"code":"PAGE_INVALID","field":"page"},
                     {"code":"SORT_NOT_ALLOWED","field":"sort"}]}""",
            ),
            response.json(),
        )
    }
}
