package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.CityDto
import com.kiva.application.dto.ProvinceDto
import com.kiva.application.usecase.geo.usecase.query.ListCitiesOfProvinceUc
import com.kiva.application.usecase.geo.usecase.query.ListProvincesUc
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WebV1GeoTest {

    private val listProvincesUc = mockk<ListProvincesUc>()
    private val listCitiesOfProvinceUc = mockk<ListCitiesOfProvinceUc>()

    /** The real HTTP setup (JSON, CallId, StatusPages) with only the geo routes; no database, no Koin. */
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

    @AfterEach
    fun tearDown() = unmockkAll()

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `GET provinces returns the contract's Province with its City list, cached`() = withApp {
        coEvery { listProvincesUc.execute(ListProvincesUc.Qry(null)) } returns Result.success(
            listOf(ProvinceDto(8, "تهران", listOf(CityDto(394, "تهران", 8)))),
        )

        val response = client.get("$API_V1/geo/provinces")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json("""[{"id":8,"name":"تهران","cities":[{"id":394,"name":"تهران","provinceId":8}]}]"""), response.json())
        assertNotNull(response.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `includeCities goes to the use case as it came, and provinces alone carry cities null, not left out`() = withApp {
        coEvery { listProvincesUc.execute(ListProvincesUc.Qry("false")) } returns Result.success(listOf(ProvinceDto(8, "تهران", null)))

        val response = client.get("$API_V1/geo/provinces?includeCities=false")

        assertEquals(json("""[{"id":8,"name":"تهران","cities":null}]"""), response.json())
        coVerify(exactly = 1) { listProvincesUc.execute(ListProvincesUc.Qry("false")) }
    }

    @Test
    fun `a wrong includeCities is a 400 VALIDATION_ERROR naming the field, not cached`() = withApp {
        coEvery { listProvincesUc.execute(ListProvincesUc.Qry("abc")) } returns Result.failure(
            ListProvincesUc.Failure.InvalidInput(listOf(FieldFailure("includeCities", ListProvincesUc.Failure.BooleanInvalid))),
        )

        val response = client.get("$API_V1/geo/provinces?includeCities=abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.json().jsonObject
        assertEquals("VALIDATION_ERROR", body["code"]!!.jsonPrimitive.content)
        val error = body["errors"]!!.jsonArray.single().jsonObject
        assertEquals("includeCities", error["field"]!!.jsonPrimitive.content)
        assertEquals("BOOLEAN_INVALID", error["code"]!!.jsonPrimitive.content)
        assertEquals(null, response.headers[HttpHeaders.ETag])
    }

    @Test
    fun `GET a province's cities returns the contract's City list, cached`() = withApp {
        coEvery { listCitiesOfProvinceUc.execute(ListCitiesOfProvinceUc.Qry("8")) } returns Result.success(listOf(CityDto(394, "تهران", 8)))

        val response = client.get("$API_V1/geo/provinces/8/cities")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json("""[{"id":394,"name":"تهران","provinceId":8}]"""), response.json())
        assertNotNull(response.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `an unknown province is 404 NOT_FOUND`() = withApp {
        coEvery { listCitiesOfProvinceUc.execute(ListCitiesOfProvinceUc.Qry("abc")) } returns
            Result.failure(ListCitiesOfProvinceUc.Failure.ProvinceNotFound)

        val response = client.get("$API_V1/geo/provinces/abc/cities")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.json().jsonObject["code"]!!.jsonPrimitive.content)
    }
}
