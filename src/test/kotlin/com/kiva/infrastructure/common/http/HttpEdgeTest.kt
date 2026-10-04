package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpEdgeTest {

    @Serializable
    private data class ProductHttpResponse(val name: String, val price: Long)

    @Serializable
    private data class CreateProductHttpRequest(val name: String, val price: Long)

    private class TestFailure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, field, causes)

    /** Starts the real HTTP setup (JSON + StatusPages) with a few test routes, without DB or Koin. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        // Without this, testApplication would load application.conf and boot the whole app (database included).
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                get("/product") {
                    call.respondResult(Result.success("Mug")) { ProductHttpResponse(it, 250_000) }
                }
                get("/created") {
                    call.respondResult(Result.success(42L), HttpStatusCode.Created) { mapOf("id" to it) }
                }
                get("/done") {
                    call.respondResult(Result.success(Unit))
                }
                get("/fail/{kind}") {
                    val kind = FailureKind.valueOf(call.parameters["kind"]!!)
                    call.respondResult(Result.failure(TestFailure("SOMETHING_WRONG", kind))) { it: String -> it }
                }
                get("/invalid-address") {
                    val failure = TestFailure(
                        "INVALID_ADDRESS", FailureKind.INVALID,
                        causes = listOf(
                            TestFailure("POSTAL_CODE_INVALID", FailureKind.INVALID, field = "postalCode"),
                            TestFailure("MOBILE_INVALID", FailureKind.INVALID, field = "mobile"),
                        ),
                    )
                    call.respondResult(Result.failure(failure))
                }
                post("/products") {
                    val request = call.receive<CreateProductHttpRequest>()
                    call.respondResult(Result.success(request), HttpStatusCode.Created) { ProductHttpResponse(it.name, it.price) }
                }
                get("/boom") {
                    error("connection to db.internal:5432 failed, password=s3cret")
                }
            }
        }
        block()
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Nested
    inner class Success {
        @Test
        fun `success returns 200 with the mapped body`() = withApp {
            val response = client.get("/product")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(json("""{"name":"Mug","price":250000}"""), response.json())
        }

        @Test
        fun `success status can be chosen`() = withApp {
            val response = client.get("/created")
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(json("""{"id":42}"""), response.json())
        }

        @Test
        fun `a Unit result returns 204 with no body`() = withApp {
            val response = client.get("/done")
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("", response.bodyAsText())
        }
    }

    @Nested
    inner class Failures {
        @ParameterizedTest
        @EnumSource(FailureKind::class)
        fun `each failure kind becomes its HTTP status`(kind: FailureKind) = withApp {
            val response = client.get("/fail/$kind")
            assertEquals(kind.toHttpStatus(), response.status)
            assertEquals(json("""{"code":"SOMETHING_WRONG"}"""), response.json())
        }

        @Test
        fun `the mapping is the agreed one`() {
            assertEquals(
                listOf(400, 404, 409, 401, 403),
                listOf(FailureKind.INVALID, FailureKind.NOT_FOUND, FailureKind.CONFLICT, FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN)
                    .map { it.toHttpStatus().value },
            )
        }

        @Test
        fun `causes and fields reach the client`() = withApp {
            val response = client.get("/invalid-address")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                json(
                    """{"code":"INVALID_ADDRESS","causes":[
                         {"code":"POSTAL_CODE_INVALID","field":"postalCode"},
                         {"code":"MOBILE_INVALID","field":"mobile"}]}""",
                ),
                response.json(),
            )
        }
    }

    @Nested
    inner class BrokenRequests {
        @Test
        fun `a valid body is read`() = withApp {
            val response = client.post("/products") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Mug","price":250000}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                """{"name":"Mug",""",                    // broken JSON
                """{"name":"Mug"}""",                    // missing required field
                """{"name":"Mug","price":"cheap"}""",    // wrong type
                "",                                      // empty body
            ],
        )
        fun `an unreadable body is a 400, not a 500`(body: String) = withApp {
            val response = client.post("/products") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(json("""{"code":"MALFORMED_REQUEST"}"""), response.json())
        }
    }

    @Nested
    inner class UnexpectedErrors {
        @Test
        fun `an exception becomes a 500 with only a code`() = withApp {
            val response = client.get("/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(json("""{"code":"INTERNAL_ERROR"}"""), response.json())
        }

        @Test
        fun `nothing from the exception leaks to the client`() = withApp {
            val body = client.get("/boom").bodyAsText()
            assertFalse("s3cret" in body)
            assertFalse("db.internal" in body)
            assertFalse("IllegalStateException" in body)
            assertTrue(body.length < 40)
        }
    }
}
