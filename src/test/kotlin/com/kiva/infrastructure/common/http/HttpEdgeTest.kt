package com.kiva.infrastructure.common.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.infrastructure.httpserver.TRACE_ID_HEADER
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpEdgeTest {

    @Serializable
    private data class ProductHttpResponse(val name: String, val price: Long)

    @Serializable
    private data class CreateProductHttpRequest(val name: String, val price: Long)

    private class TestFailure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, field, causes, meta)

    /** Starts the real HTTP setup (JSON + CallId + StatusPages) with a few test routes, without DB or Koin. */
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
                    call.respondResult(Result.failure(TestFailure("SOMETHING_WRONG", kind)))
                }
                get("/product-not-found") {
                    call.respondResult(Result.failure(TestFailure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)))
                }
                get("/low-stock") {
                    call.respondResult(
                        Result.failure(TestFailure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, meta = mapOf("availableQuantity" to 3))),
                    )
                }
                get("/invalid-address") {
                    val failure = TestFailure(
                        "INVALID_ADDRESS", FailureKind.INVALID, field = "newAddress",
                        causes = listOf(
                            TestFailure("POSTAL_CODE_INVALID", FailureKind.INVALID, field = "postalCode"),
                            TestFailure("PHONE_INVALID", FailureKind.INVALID, field = "recipientPhone"),
                        ),
                    )
                    call.respondResult(Result.failure(failure))
                }
                get("/invalid-without-field") {
                    call.respondResult(Result.failure(TestFailure("DISCOUNT_CODE_INVALID", FailureKind.INVALID)))
                }
                post("/products") {
                    val request = call.receive<CreateProductHttpRequest>()
                    call.respondResult(Result.success(request), HttpStatusCode.Created) { ProductHttpResponse(it.name, it.price) }
                }
                get("/raced") {
                    throw ConcurrentChangeException(EventAggregateName("PRODUCT"), EventAggregateId.of(7))
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

    /** The problem body without the per-request parts (traceId, instance), so it can be compared exactly. */
    private suspend fun HttpResponse.problem(): JsonElement = JsonObject(json().jsonObject - "traceId" - "instance")

    private fun HttpResponse.isProblemJson() = contentType()?.withoutParameters() == ContentType.Application.ProblemJson

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

        @Test
        fun `every response carries a trace id header`() = withApp {
            assertNotNull(client.get("/product").headers[TRACE_ID_HEADER])
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `the kind to status mapping follows the contract`() {
            assertEquals(
                mapOf(
                    FailureKind.INVALID to 400, FailureKind.UNAUTHORIZED to 401, FailureKind.FORBIDDEN to 403,
                    FailureKind.NOT_FOUND to 404, FailureKind.CONFLICT to 409, FailureKind.BUSINESS_RULE to 422,
                    FailureKind.RATE_LIMITED to 429,
                ),
                FailureKind.entries.associateWith { it.toHttpStatus().value },
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["NOT_FOUND", "CONFLICT", "BUSINESS_RULE", "RATE_LIMITED", "UNAUTHORIZED", "FORBIDDEN"])
        fun `a failure is a problem with its kind's status and code`(kind: String) = withApp {
            val response = client.get("/fail/$kind")
            val status = FailureKind.valueOf(kind).toHttpStatus()

            assertEquals(status, response.status)
            assertTrue(response.isProblemJson())
            assertEquals(json("""{"status":${status.value},"code":"SOMETHING_WRONG","errors":null,"meta":null}"""), JsonObject(response.problem().jsonObject - "message"))
        }

        @Test
        fun `a known code gets its Persian message, the path and a trace id`() = withApp {
            val response = client.get("/product-not-found")
            val body = response.json().jsonObject

            assertEquals(json("""{"status":404,"code":"PRODUCT_NOT_FOUND","message":"این محصول پیدا نشد","errors":null,"meta":null}"""), response.problem())
            assertEquals("/product-not-found", body.getValue("instance").jsonPrimitive.content)
            assertEquals(response.headers[TRACE_ID_HEADER], body.getValue("traceId").jsonPrimitive.content)
        }

        @Test
        fun `meta reaches the client and fills the message`() = withApp {
            assertEquals(
                json("""{"status":422,"code":"QUANTITY_EXCEEDS_STOCK","message":"فقط ۳ عدد از این کالا موجوده","meta":{"availableQuantity":3},"errors":null}"""),
                client.get("/low-stock").problem(),
            )
        }

        @Test
        fun `field problems become VALIDATION_ERROR with every field listed by its full path`() = withApp {
            val response = client.get("/invalid-address")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                json(
                    """{"status":400,"code":"VALIDATION_ERROR","message":"چند تا از فیلدها رو باید درست کنی","errors":[
                         {"field":"newAddress.postalCode","code":"POSTAL_CODE_INVALID","message":"کد پستی باید ۱۰ رقم باشه"},
                         {"field":"newAddress.recipientPhone","code":"PHONE_INVALID","message":"موبایل معتبر وارد کن"}],"meta":null}""",
                ),
                response.problem(),
            )
        }

        @Test
        fun `an invalid failure without any field keeps its own code`() = withApp {
            assertEquals(
                json("""{"status":400,"code":"DISCOUNT_CODE_INVALID","message":"این کد تخفیف معتبر نیست","errors":null,"meta":null}"""),
                client.get("/invalid-without-field").problem(),
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
        fun `an unreadable body is a 400 VALIDATION_ERROR, not a 500`(body: String) = withApp {
            val response = client.post("/products") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.isProblemJson())
            assertEquals(json("""{"status":400,"code":"VALIDATION_ERROR","message":"اطلاعات ارسالی قابل خوندن نیست","errors":null,"meta":null}"""), response.problem())
        }

        @Test
        fun `a body that is not JSON is a 415`() = withApp {
            val response = client.post("/products") {
                contentType(ContentType.Text.Plain)
                setBody("name=Mug")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertEquals("VALIDATION_ERROR", response.json().jsonObject.getValue("code").jsonPrimitive.content)
        }

        @Test
        fun `an unknown route is a 404 problem`() = withApp {
            val response = client.get("/no-such-route")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.isProblemJson())
            assertEquals(json("""{"status":404,"code":"NOT_FOUND","message":"چیزی که دنبالش بودی پیدا نشد","errors":null,"meta":null}"""), response.problem())
        }
    }

    @Nested
    inner class Races {
        @Test
        fun `a change someone else saved first is a 409 CHANGED_BY_SOMEONE_ELSE problem, not a 500`() = withApp {
            val response = client.get("/raced")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.isProblemJson())
            assertEquals(
                json("""{"status":409,"code":"CHANGED_BY_SOMEONE_ELSE","message":"این مورد همین الان توسط کس دیگه‌ای تغییر کرد. صفحه رو تازه کن و دوباره امتحان کن","errors":null,"meta":null}"""),
                response.problem(),
            )
        }
    }

    @Nested
    inner class UnexpectedErrors {
        @Test
        fun `an exception becomes a 500 with only a code and a message`() = withApp {
            val response = client.get("/boom")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(
                json("""{"status":500,"code":"INTERNAL_ERROR","message":"یه مشکلی پیش اومد. چند لحظه بعد دوباره امتحان کن","errors":null,"meta":null}"""),
                response.problem(),
            )
        }

        @Test
        fun `nothing from the exception leaks to the client`() = withApp {
            val body = client.get("/boom").bodyAsText()
            assertFalse("s3cret" in body)
            assertFalse("db.internal" in body)
            assertFalse("IllegalStateException" in body)
        }

        @Test
        fun `the log names the path without its query string, which can be a customer's phone`() {
            val logger = LoggerFactory.getLogger("com.kiva.infrastructure.common.http.StatusPages") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                withApp { client.get("/boom?q=09121234567") }
            } finally {
                logger.detachAppender(appender)
            }
            assertEquals(listOf("Unhandled error on GET /boom"), appender.list.map { it.formattedMessage })
        }
    }
}
