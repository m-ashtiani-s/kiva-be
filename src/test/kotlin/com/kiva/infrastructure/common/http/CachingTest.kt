package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachingTest {

    @Serializable
    private data class Body(val name: String, val note: String?)

    private data object Missing : ResultFailure("NOT_FOUND", FailureKind.NOT_FOUND)

    private var body = Body("تهران", null)

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                get("/cached") { call.respondResultCacheable(Result.success(body)) { it } }
                get("/missing") { call.respondResultCacheable(Result.failure(Missing)) { _: Body -> body } }
            }
        }
        block()
    }

    @Test
    fun `the tag is weak, the same for the same body and different for another`() {
        val tag = weakEtagOf("""{"name":"تهران"}""")

        assertTrue(Regex("""W/"[0-9a-f]{32}"""").matches(tag), tag)
        assertEquals(tag, weakEtagOf("""{"name":"تهران"}"""))
        assertNotEquals(tag, weakEtagOf("""{"name":"اصفهان"}"""))
    }

    @Test
    fun `If-None-Match is compared weakly, in a list, and star matches anything`() {
        val tag = "W/\"abc\""

        assertTrue(ifNoneMatchHits("W/\"abc\"", tag))
        assertTrue(ifNoneMatchHits("\"abc\"", tag))
        assertTrue(ifNoneMatchHits("W/\"x\", W/\"abc\"", tag))
        assertTrue(ifNoneMatchHits("*", tag))
        assertFalse(ifNoneMatchHits("W/\"abd\"", tag))
        assertFalse(ifNoneMatchHits("", tag))
    }

    @Test
    fun `a success is sent as JSON with its ETag and Cache-Control, null fields included`() = withApp {
        val response = client.get("/cached")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"name":"تهران","note":null}""", response.bodyAsText())
        assertEquals(weakEtagOf("""{"name":"تهران","note":null}"""), response.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
    }

    @Test
    fun `asking again with the tag gets 304 with no body, and the same headers`() = withApp {
        val tag = client.get("/cached").headers[HttpHeaders.ETag]!!

        val response = client.get("/cached") { header(HttpHeaders.IfNoneMatch, tag) }

        assertEquals(HttpStatusCode.NotModified, response.status)
        assertEquals("", response.bodyAsText())
        assertEquals(tag, response.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `when the data changed, the old tag gets the new body with a new tag`() = withApp {
        val oldTag = client.get("/cached").headers[HttpHeaders.ETag]!!
        body = Body("تهران", "پایتخت")

        val response = client.get("/cached") { header(HttpHeaders.IfNoneMatch, oldTag) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"name":"تهران","note":"پایتخت"}""", response.bodyAsText())
        assertNotEquals(oldTag, response.headers[HttpHeaders.ETag])
    }

    @Test
    fun `a failure is a normal problem, never cached`() = withApp {
        val response = client.get("/missing")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertNull(response.headers[HttpHeaders.ETag])
        assertNull(response.headers[HttpHeaders.CacheControl])
    }
}
