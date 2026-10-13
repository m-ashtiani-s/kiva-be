package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.geo.usecase.query.ListCitiesOfProvinceUc
import com.kiva.application.usecase.geo.usecase.query.ListProvincesUc
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geo routes over the real stack on kiva_test: routes, use cases and the lists V11__geo.sql fills. Step 1 of the
 * purchase roadmap is done when these checks pass.
 */
class WebV1GeoOnDatabaseTest {

    private val db = TestDatabase.database
    private val listProvincesUc = ListProvincesUc(ExposedTransactionManager(db), GeoRepositoryImpl())
    private val listCitiesOfProvinceUc = ListCitiesOfProvinceUc(ExposedTransactionManager(db), GeoRepositoryImpl())

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                route(API_V1) { webV1Geo(listProvincesUc, listCitiesOfProvinceUc) }
            }
        }
        block()
    }

    private suspend fun HttpResponse.jsonArray(): JsonArray = Json.parseToJsonElement(bodyAsText()).jsonArray

    @Test
    fun `GET provinces gives the 31 provinces in alphabetical order, each with its cities, 1319 in all`() = withApp {
        val provinces = client.get("$API_V1/geo/provinces").jsonArray()

        assertEquals(31, provinces.size)
        assertEquals("آذربایجان شرقی", provinces.first().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("یزد", provinces.last().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1319, provinces.sumOf { it.jsonObject["cities"]!!.jsonArray.size })
        val names = provinces.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("چهارمحال و بختیاری" in names && "سیستان و بلوچستان" in names && "کهگیلویه و بویراحمد" in names)
    }

    @Test
    fun `includeCities=false gives cities null on every province`() = withApp {
        val provinces = client.get("$API_V1/geo/provinces?includeCities=false").jsonArray()

        assertEquals(31, provinces.size)
        assertTrue(provinces.all { it.jsonObject["cities"] == JsonNull })
    }

    @Test
    fun `Tehran's cities have the city of Tehran as 394, without Tabriz's districts anywhere`() = withApp {
        val tehran = client.get("$API_V1/geo/provinces/8/cities").jsonArray()
        val tabriz = client.get("$API_V1/geo/provinces/1/cities").jsonArray().map { it.jsonObject["name"]!!.jsonPrimitive.content }

        val city = tehran.single { it.jsonObject["name"]!!.jsonPrimitive.content == "تهران" }.jsonObject
        assertEquals(394, city["id"]!!.jsonPrimitive.int)
        assertEquals(8, city["provinceId"]!!.jsonPrimitive.int)
        assertTrue("تبریز" in tabriz)
        assertTrue(tabriz.none { it.startsWith("تبریز") && it != "تبریز" })
    }

    @Test
    fun `a province that doesn't exist, or a segment that is not a number, is 404 NOT_FOUND`() = withApp {
        for (segment in listOf("99", "abc")) {
            val response = client.get("$API_V1/geo/provinces/$segment/cities")

            assertEquals(HttpStatusCode.NotFound, response.status, segment)
            assertTrue(response.bodyAsText().contains("\"code\":\"NOT_FOUND\""), segment)
        }
    }

    @Test
    fun `asking again with the ETag gets 304 with no body, and Cache-Control is public for five minutes`() = withApp {
        for (path in listOf("/geo/provinces", "/geo/provinces/8/cities")) {
            val first = client.get("$API_V1$path")
            assertEquals("public, max-age=300", first.headers[HttpHeaders.CacheControl], path)

            val second = client.get("$API_V1$path") { header(HttpHeaders.IfNoneMatch, first.headers[HttpHeaders.ETag]!!) }

            assertEquals(HttpStatusCode.NotModified, second.status, path)
            assertEquals("", second.bodyAsText(), path)
        }
    }
}
