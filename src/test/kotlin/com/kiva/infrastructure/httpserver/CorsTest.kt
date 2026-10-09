package com.kiva.infrastructure.httpserver

import com.kiva.infrastructure.config.HttpConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browser rules for a frontend on another origin (kiva.http.allowedOrigins). */
class CorsTest {

    private val shop = "https://kiva.ir"
    private val devServer = "http://localhost:5173"

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp(allowedOrigins = listOf(shop, devServer))
            routing {
                get("/api/v1/colors") { call.respondText("[]") }
                post("/api/v1/orders") { call.respondText("{}") }
            }
        }
        block()
    }

    /** What the browser sends before a POST with a JSON body and an Idempotency-Key. */
    private suspend fun ApplicationTestBuilder.preflight(origin: String) = client.options("/api/v1/orders") {
        header(HttpHeaders.Origin, origin)
        header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
        header(HttpHeaders.AccessControlRequestHeaders, "content-type,idempotency-key,authorization")
    }

    @Nested
    inner class Preflight {
        @Test
        fun `an allowed frontend may send a JSON POST with an Idempotency-Key`() = withApp {
            val response = preflight(shop)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(shop, response.headers[HttpHeaders.AccessControlAllowOrigin])
            val allowedHeaders = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
            listOf("content-type", "idempotency-key", "authorization").forEach { assertTrue(it in allowedHeaders, "$it not allowed: $allowedHeaders") }
        }

        /** Without it the browser neither keeps the refresh token's cookie from a login nor sends it back. */
        @Test
        fun `an allowed frontend may send and receive cookies`() = withApp {
            assertEquals("true", preflight(shop).headers[HttpHeaders.AccessControlAllowCredentials])
        }

        @Test
        fun `the local dev server is allowed too`() = withApp {
            assertEquals(devServer, preflight(devServer).headers[HttpHeaders.AccessControlAllowOrigin])
        }

        @Test
        fun `any other site gets no permission`() = withApp {
            val response = preflight("https://evil.example")
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    @Nested
    inner class SimpleRequests {
        @Test
        fun `the frontend's JavaScript may read the trace id`() = withApp {
            val response = client.get("/api/v1/colors") { header(HttpHeaders.Origin, shop) }

            assertEquals(shop, response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertTrue(TRACE_ID_HEADER.lowercase() in response.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().lowercase())
        }

        @Test
        fun `a request without an Origin (curl, server to server) is answered as before`() = withApp {
            val response = client.get("/api/v1/colors")
            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }

    @Nested
    inner class Config {
        @Test
        fun `origins come comma-separated, spaces and empty pieces ignored`() {
            assertEquals(listOf(shop, devServer), HttpConfig.of(" $shop , ,$devServer ").allowedOrigins)
        }

        @Test
        fun `a wildcard, a path or a missing scheme stops the boot`() {
            listOf("*", "https://kiva.ir/", "kiva.ir", "https://*.kiva.ir").forEach { bad ->
                assertFailsWith<IllegalArgumentException>(bad) { HttpConfig.of(bad) }
            }
        }
    }
}
