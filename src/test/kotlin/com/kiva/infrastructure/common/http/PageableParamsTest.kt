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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PageableParamsTest {

    /** A route that echoes back how it understood the query parameters. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                get("/products") {
                    val pageable = call.pageable(allowedSorts = setOf("price", "name"))
                        .unwrapOr { return@get call.respondFailure(it) }
                    call.respond(
                        mapOf(
                            "page" to pageable.page.toString(),
                            "size" to pageable.size.toString(),
                            "sort" to pageable.sort.joinToString(",") { "${it.field}:${it.direction}" },
                        ),
                    )
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
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `no parameters gives page 1 with the default size and no sort`() = withApp {
        assertEquals(json("""{"page":"1","size":"20","sort":""}"""), client.get("/products").json())
    }

    @Test
    fun `reads page, size and a multi-field sort with direction`() = withApp {
        assertEquals(
            json("""{"page":"2","size":"10","sort":"price:DESC,name:ASC"}"""),
            client.get("/products?page=2&size=10&sort=-price,name").json(),
        )
    }

    @Test
    fun `a page that is not a number is a 400`() = withApp {
        val response = client.get("/products?page=abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(json("""{"code":"INVALID_PAGINATION","causes":[{"code":"PAGE_INVALID","field":"page"}]}"""), response.json())
    }

    @Test
    fun `a huge size is a 400, not silently reduced`() = withApp {
        val response = client.get("/products?size=1000000")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(json("""{"code":"INVALID_PAGINATION","causes":[{"code":"PAGE_SIZE_INVALID","field":"size"}]}"""), response.json())
    }

    @Test
    fun `sorting by a column that is not allowed is a 400`() = withApp {
        val response = client.get("/products?sort=-password")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(json("""{"code":"INVALID_PAGINATION","causes":[{"code":"SORT_NOT_ALLOWED","field":"sort"}]}"""), response.json())
    }

    @Test
    fun `a page is sent with its page information`() = withApp {
        assertEquals(
            json("""{"items":["item 1","item 2"],"page":2,"size":2,"totalItems":5,"totalPages":3}"""),
            client.get("/page").json(),
        )
    }
}
