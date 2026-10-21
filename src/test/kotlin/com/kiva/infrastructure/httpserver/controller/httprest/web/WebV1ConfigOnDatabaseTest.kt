package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredSettings
import com.kiva.infrastructure.persistence.exposed.tbl.AnnouncementTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /config` (step 5.4) on kiva_test: every part of the contract's SiteConfig, the settings it shows being the same rows
 * the panel saves, «الان پاسخگوییم» by Tehran's clock, and only the announcements whose time it is.
 */
class WebV1ConfigOnDatabaseTest {

    // Wednesday 2026-10-21, 11:30 in Tehran.
    private val clock = MutableClock(Instant.parse("2026-10-21T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val db = TestDatabase.database
    private val original: ShopSettings = StoredSettings.read(db)

    @AfterEach
    fun deleteWhatWasStored() {
        StoredSettings.restore(db, original)
        panel.deleteWhatWasStored()
        transaction(db) { AnnouncementTbl.deleteAll() }
    }

    private val admin by lazy { panel.callerWith(Role.ADMIN) }

    private suspend fun ApplicationTestBuilder.config(ifNoneMatch: String? = null): HttpResponse =
        client.get("$API_V1/config") { ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) } }

    private suspend fun ApplicationTestBuilder.json(response: HttpResponse): JsonObject {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** The panel's settings with [changes], saved as the panel saves them. */
    private suspend fun ApplicationTestBuilder.changeSettings(vararg changes: Pair<String, JsonElement>) {
        val current = Json.parseToJsonElement(client.get("$API_V1/admin/settings") { header(HttpHeaders.Authorization, admin.bearer) }.bodyAsText()).jsonObject
        val saved = client.put("$API_V1/admin/settings") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(current + changes).toString())
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.saveAnnouncements(body: String) {
        val saved = client.put("$API_V1/admin/announcements") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
    }

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.list(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

    private val hours = Json.parseToJsonElement(
        """[{"label":"شنبه تا پنجشنبه","weekdays":["SATURDAY","SUNDAY","MONDAY","TUESDAY","WEDNESDAY","THURSDAY"],"from":"09:00","to":"21:00","messengerOnly":false},
            {"label":"جمعه","weekdays":["FRIDAY"],"from":"10:00","to":"14:00","messengerOnly":true}]""",
    )

    @Test
    fun `every part of SiteConfig is there, each with every field of its own`() = panel.app {
        val config = json(config())

        assertEquals(ContractSchemas.propertiesOf("SiteConfig"), config.keys)
        val parts = mapOf(
            "brand" to setOf("name", "slogan", "about", "logoUrl", "logoWhiteUrl", "faviconUrl"),
            "currency" to setOf("code", "label"),
            "shipping" to setOf("methods", "freeShippingThreshold", "freeShippingMethods", "nonShippingWeekdays"),
            "reservation" to setOf("enabled", "holdDays", "description"),
            "returns" to setOf("windowDays", "policyUrl"),
            "preShipmentPhoto" to setOf("enabled", "required", "channels", "title", "description"),
            "inventory" to setOf("lowStockThreshold"),
            "auth" to setOf("otpLength", "resendCooldownSeconds"),
            "support" to setOf("phone", "phoneDisplay", "email", "emailResponseHint", "responseTimeHint", "hours", "isOpenNow"),
        )
        parts.forEach { (part, keys) -> assertEquals(keys, config.obj(part).keys, part) }
        assertEquals(ContractSchemas.propertiesOf("ShippingMethod"), config.obj("shipping").list("methods").first().keys)
        assertEquals(ContractSchemas.propertiesOf("PaymentGateway"), config.list("paymentGateways").first().keys)
        assertEquals(ContractSchemas.propertiesOf("MessengerOption"), config.obj("preShipmentPhoto").list("channels").first().keys)
        assertEquals(setOf("wishlistShare", "reviewMedia", "giftWrap"), config.obj("features").keys)
        assertEquals(listOf("IRT", "تومان"), listOf(config.obj("currency").text("code"), config.obj("currency").text("label")))
        assertEquals(listOf("5", "120"), listOf(config.obj("auth").text("otpLength"), config.obj("auth").text("resendCooldownSeconds")))
    }

    @Test
    fun `the site's texts have their settings filled and the brand's files the site's address`() = panel.app {
        val config = json(config())

        assertEquals("${PanelTestStack.SITE_URL}/assets/kiva-logo-primary.svg", config.obj("brand").text("logoUrl"))
        assertEquals("الان کامل پرداخت کن، ۴ روز بعد همه‌ی خریدهات یکجا برات ارسال می‌شه.", config.obj("reservation").text("description"))
        assertEquals(listOf("ارسال سریع", "رزرو ۴ روزه", "۷ روز بازگشت"), config.list("productPerks").map { it.text("title") })
        assertEquals("رزرو ۴ روزه", config.list("footerLinks").first().list("links")[3].text("label"))
        assertEquals(emptyList(), config.list("trustBadges"))
    }

    @Test
    fun `changed in the panel, changed here - threshold, costs, the active methods only, hold days in the texts, support`() = panel.app {
        val before = json(config())
        assertEquals(listOf("TIPAX", "POST"), before.obj("shipping").list("methods").map { it.text("code") })

        val settings = Json.parseToJsonElement(client.get("$API_V1/admin/settings") { header(HttpHeaders.Authorization, admin.bearer) }.bodyAsText()).jsonObject
        val methods = settings.getValue("shippingMethods").jsonArray.map { method ->
            val m = method.jsonObject
            when (m.text("code")) {
                "TIPAX" -> JsonObject(m + ("isActive" to JsonPrimitive(false)))
                else -> JsonObject(m + ("baseCost" to JsonPrimitive(80_000)))
            }
        }
        changeSettings(
            "shippingMethods" to JsonArray(methods),
            "defaultShippingMethod" to JsonPrimitive("POST"),
            "freeShippingThreshold" to JsonPrimitive(2_500_000),
            "reservationHoldDays" to JsonPrimitive(5),
            "supportPhone" to JsonPrimitive("09123456789"),
            "supportEmail" to JsonPrimitive("help@kiva.ir"),
        )

        val after = json(config())
        val shipping = after.obj("shipping")
        assertEquals(listOf("POST" to "80000"), shipping.list("methods").map { it.text("code") to it.text("baseCost") })
        assertEquals("2500000", shipping.text("freeShippingThreshold"))
        // The cart reads the same rows: its methods and their costs are these.
        val customer = panel.callerWith(Role.CUSTOMER)
        val cart = json(client.get("$API_V1/cart") { header(HttpHeaders.Authorization, customer.bearer) })
        assertEquals(
            shipping.list("methods").map { it.text("code") to it.text("baseCost") },
            cart.list("shippingOptions").filter { it.text("available") == "true" }.map { it.text("method") to it.text("baseCost") },
        )
        assertEquals("5", after.obj("reservation").text("holdDays"))
        assertTrue("۵ روز" in after.obj("reservation").text("description")!!, after.obj("reservation").text("description"))
        assertEquals(
            listOf("09123456789", "۰۹۱۲-۳۴۵-۶۷۸۹", "help@kiva.ir"),
            listOf("phone", "phoneDisplay", "email").map { after.obj("support").text(it) },
        )
    }

    @Test
    fun `isOpenNow by Tehran's clock - Saturday 10 00 yes, 22 00 no, Friday's messengers-only line no`() = panel.app {
        changeSettings("supportHours" to hours)
        // In the order they come, as the clock only moves on.
        val cases = listOf(
            "2026-10-23T07:30:00Z" to "false", // Friday 11:00 in Tehran, messengers only
            "2026-10-24T06:30:00Z" to "true", // Saturday 10:00
            "2026-10-24T18:30:00Z" to "false", // Saturday 22:00
        )

        val seen = cases.map { (moment, _) ->
            clock.advance(Duration.between(clock.now, Instant.parse(moment)))
            json(config()).obj("support").text("isOpenNow")
        }

        assertEquals(cases.map { it.second }, seen)
        assertEquals(2, json(config()).obj("support").list("hours").size)
    }

    @Test
    fun `only the announcements whose time it is - not tomorrow's, not an ended one`() = panel.app {
        saveAnnouncements(
            """[{"text":"همیشه"},{"text":"فردا","startsAt":"2026-10-22T08:00:00Z"},{"text":"تمام‌شده","endsAt":"2026-10-21T07:00:00Z"},
                {"text":"تا فردا","endsAt":"2026-10-22T08:00:00Z"}]""",
        )

        val shown = json(config()).list("announcements")

        assertEquals(listOf("همیشه", "تا فردا"), shown.map { it.text("text") })
        assertEquals(ContractSchemas.propertiesOf("Announcement"), shown.first().keys)
    }

    @Test
    fun `cached - its ETag again is 304, and a change in the panel gives another`() = panel.app {
        val first = config()
        val etag = first.headers[HttpHeaders.ETag]!!
        assertEquals("public, max-age=300", first.headers[HttpHeaders.CacheControl])

        assertEquals(HttpStatusCode.NotModified, config(ifNoneMatch = etag).status)

        saveAnnouncements("""[{"text":"تازه"}]""")
        assertEquals(HttpStatusCode.OK, config(ifNoneMatch = etag).status)
    }
}
