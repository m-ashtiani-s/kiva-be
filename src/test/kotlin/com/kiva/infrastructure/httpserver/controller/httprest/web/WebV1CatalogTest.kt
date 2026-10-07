package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.CategoryDto
import com.kiva.application.dto.ColorDto
import com.kiva.application.dto.MaterialDto
import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebV1CatalogTest {

    private val listColorsUc = mockk<ListColorsUc>()
    private val listMaterialsUc = mockk<ListMaterialsUc>()
    private val listCategoriesUc = mockk<ListCategoriesUc>()
    private val getCategoryUc = mockk<GetCategoryUc>()

    /** The real HTTP setup (JSON, CallId, StatusPages) with only the catalog routes; no database, no Koin. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing {
                route(API_V1) { webV1Catalog(listColorsUc, listMaterialsUc, listCategoriesUc, getCategoryUc) }
            }
        }
        block()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val shoulder = CategoryDto(1, "shoulder", "کیف دوشی", null, BagType.HOBO, "lilac", 1, showInMenu = true, productCount = 0)

    @Test
    fun `GET colors returns the palette as the contract's Color`() = withApp {
        coEvery { listColorsUc.execute(ListColorsUc.Qry) } returns Result.success(
            listOf(ColorDto("lilac", "یاسی", "#C8B6E2", isLight = true, sortOrder = 4, productCount = 0)),
        )

        val response = client.get("$API_V1/colors")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            json("""[{"key":"lilac","name":"یاسی","hex":"#C8B6E2","isLight":true,"sortOrder":4,"productCount":0}]"""),
            response.json(),
        )
    }

    @Test
    fun `GET materials returns every material as the contract's Material, family as its enum name`() = withApp {
        coEvery { listMaterialsUc.execute(ListMaterialsUc.Qry) } returns Result.success(
            listOf(MaterialDto(1, "genuine-cow-leather", "چرم طبیعی گاوی", MaterialFamily.GENUINE_LEATHER, null, emptyList(), 0)),
        )

        val response = client.get("$API_V1/materials")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            json(
                """[{"id":1,"slug":"genuine-cow-leather","name":"چرم طبیعی گاوی","family":"GENUINE_LEATHER","description":null,
                     "careInstructions":[],"productCount":0}]""",
            ),
            response.json(),
        )
    }

    @Test
    fun `GET categories returns the contract's Category, with the icon as an object and description as null when it has none`() = withApp {
        coEvery { listCategoriesUc.execute(ListCategoriesUc.Qry) } returns Result.success(
            listOf(shoulder, shoulder.copy(id = 3, slug = "cross", name = "کراس‌بادی", description = "برای دست‌های آزاد")),
        )

        val response = client.get("$API_V1/categories")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            json(
                """[{"id":1,"slug":"shoulder","name":"کیف دوشی","description":null,"icon":{"bagType":"HOBO","colorKey":"lilac"},
                     "productCount":0,"sortOrder":1,"showInMenu":true},
                    {"id":3,"slug":"cross","name":"کراس‌بادی","description":"برای دست‌های آزاد",
                     "icon":{"bagType":"HOBO","colorKey":"lilac"},"productCount":0,"sortOrder":1,"showInMenu":true}]""",
            ),
            response.json(),
        )
    }

    @Test
    fun `GET categories by slug passes the slug on as it came and returns the category`() = withApp {
        coEvery { getCategoryUc.execute(GetCategoryUc.Qry("shoulder")) } returns Result.success(shoulder)

        val response = client.get("$API_V1/categories/shoulder")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("shoulder", response.json().jsonObject["slug"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { getCategoryUc.execute(GetCategoryUc.Qry("shoulder")) }
    }

    @Test
    fun `an unknown category is a 404 problem with the Persian message`() = withApp {
        coEvery { getCategoryUc.execute(GetCategoryUc.Qry("nope")) } returns Result.failure(GetCategoryUc.Failure.CategoryNotFound)

        val response = client.get("$API_V1/categories/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Application.ProblemJson))
        val problem = response.json().jsonObject
        assertNotNull(problem["traceId"])
        assertEquals(
            json("""{"status":404,"code":"NOT_FOUND","message":"چیزی که دنبالش بودی پیدا نشد","instance":"/api/v1/categories/nope","errors":null,"meta":null}"""),
            JsonObject(problem - "traceId"),
        )
    }
}
