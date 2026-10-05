package com.kiva.infrastructure.common.http

import com.kiva.common.pagination.Page
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PageableParamsTest {

    private val productSorts = setOf("newest", "bestselling", "price_asc", "price_desc", "discount_desc", "rating_desc")

    /** Routes that echo back how they understood the query parameters. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                // Like the contract's product grid: 24 by default, at most 60, newest first.
                get("/products") {
                    val pageable = call.pageable(allowedSorts = productSorts, defaultSort = "newest", defaultSize = 24, maxSize = 60)
                        .unwrapOr { return@get call.respondFailure(it) }
                    call.respond(mapOf("page" to "${pageable.page}", "size" to "${pageable.size}", "sort" to "${pageable.sort}"))
                }
                // A list with the general defaults: 20 by default, at most 100, no sort options.
                get("/plain") {
                    val pageable = call.pageable().unwrapOr { return@get call.respondFailure(it) }
                    call.respond(mapOf("page" to "${pageable.page}", "size" to "${pageable.size}", "sort" to "${pageable.sort}"))
                }
                get("/page") {
                    val page = Page(listOf(1, 2), page = 2, size = 2, totalItems = 5)
                    call.respondResult(Result.success(page)) { it.toHttpResponse { n -> "item $n" } }
                }
            }
        }
        block()
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private suspend fun HttpResponse.problem(): JsonElement = JsonObject(json().jsonObject - "traceId" - "instance")
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `no parameters gives page 1 with the endpoint's defaults`() = withApp {
        assertEquals(json("""{"page":"1","size":"24","sort":"newest"}"""), client.get("/products").json())
        assertEquals(json("""{"page":"1","size":"20","sort":"null"}"""), client.get("/plain").json())
    }

    @Test
    fun `reads page, size and a named sort`() = withApp {
        assertEquals(
            json("""{"page":"2","size":"12","sort":"price_asc"}"""),
            client.get("/products?page=2&size=12&sort=price_asc").json(),
        )
    }

    @Test
    fun `an empty sort means the default`() = withApp {
        assertEquals(json("""{"page":"1","size":"24","sort":"newest"}"""), client.get("/products?sort=").json())
    }

    @Test
    fun `a page that is not a number is a VALIDATION_ERROR on the page field`() = withApp {
        val response = client.get("/products?page=abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            json(
                """{"status":400,"code":"VALIDATION_ERROR","message":"چند تا از فیلدها رو باید درست کنی",
                    "errors":[{"field":"page","code":"PAGE_INVALID","message":"شماره‌ی صفحه درست نیست"}]}""",
            ),
            response.problem(),
        )
    }

    @Test
    fun `a size above the endpoint's limit is rejected and the message says the limit`() = withApp {
        val response = client.get("/products?size=61")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            json("""[{"field":"size","code":"PAGE_SIZE_INVALID","message":"تعداد آیتم‌های هر صفحه حداکثر ۶۰ تاست"}]"""),
            response.json().jsonObject.getValue("errors"),
        )
    }

    @Test
    fun `a sort that is not one of the options is rejected`() = withApp {
        val errors = client.get("/products?sort=password").json().jsonObject.getValue("errors")
        assertEquals(
            json("""[{"field":"sort","code":"SORT_NOT_ALLOWED","message":"این نوع مرتب‌سازی پشتیبانی نمی‌شه"}]"""),
            errors,
        )
    }

    @Test
    fun `every problem is listed at once`() = withApp {
        val errors = client.get("/products?page=0&size=0&sort=x").json().jsonObject.getValue("errors").toString()
        listOf("\"page\"", "\"size\"", "\"sort\"").forEach { assertEquals(true, it in errors, "missing $it in $errors") }
    }

    @Test
    fun `a page is sent as items plus meta`() = withApp {
        assertEquals(
            json("""{"items":["item 1","item 2"],"meta":{"page":2,"size":2,"totalItems":5,"totalPages":3,"hasNext":true}}"""),
            client.get("/page").json(),
        )
    }
}
