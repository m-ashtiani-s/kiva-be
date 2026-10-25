package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredSettings
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The site's numbers and the satisfaction summary over kiva_test (roadmap step 11.5): whatever the panel types in the
 * settings is what `GET /site/stats` and `GET /testimonials` say, nothing counted.
 */
class WebV1SiteStatsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val settings = StoredSettings.read(db)

    @AfterEach
    fun deleteWhatWasStored() {
        StoredSettings.restore(db, settings)
        panel.deleteWhatWasStored()
    }

    @Test
    fun `before anything is typed the zeros and no summary - PUT settings with new numbers - site stats and the testimonials' summary say the same`() = panel.app {
        val before = Json.parseToJsonElement(client.get("$API_V1/site/stats").bodyAsText()).jsonObject
        assertEquals(ContractSchemas.propertiesOf("SiteStats"), before.keys)
        assertEquals(JsonNull, Json.parseToJsonElement(client.get("$API_V1/testimonials").bodyAsText()).jsonObject["summary"])

        val admin = panel.callerWith(Role.ADMIN)
        val current = Json.parseToJsonElement(client.get("$API_V1/admin/settings") { header(HttpHeaders.Authorization, admin.bearer) }.bodyAsText()).jsonObject
        val stats = Json.parseToJsonElement(
            """{"happyCustomers":12000,"preShipmentPhotos":48000,"photoMatchSatisfactionPercent":98,"productModels":60,
               "satisfaction":{"averageRating":4.9,"scale":5,"totalCount":2480,"metrics":[{"key":"PHOTO_MATCH","percent":98},{"key":"QUALITY","percent":96}],"sources":["TELEGRAM","BALE"]}}""",
        ).jsonObject
        val saved = client.put("$API_V1/admin/settings") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(current + ("siteStats" to stats)).toString())
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())

        val after = Json.parseToJsonElement(client.get("$API_V1/site/stats").bodyAsText()).jsonObject
        val summary = Json.parseToJsonElement(client.get("$API_V1/testimonials").bodyAsText()).jsonObject.getValue("summary")
        assertEquals(listOf("12000", "48000", "98", "60"), listOf("happyCustomers", "preShipmentPhotos", "photoMatchSatisfactionPercent", "productModels").map { after.getValue(it).toString() })
        assertEquals(after.getValue("satisfaction"), summary)
        assertEquals("4.9", summary.jsonObject.getValue("averageRating").toString())
    }
}
