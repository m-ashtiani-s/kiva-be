package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.settings.usecase.command.UpdateSettingsUc
import com.kiva.application.usecase.settings.usecase.query.GetAdminSettingsUc
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredSettings
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shop's settings in the panel (`GET`/`PUT /admin/settings`; ADMIN) on kiva_test: the whole document, its limits,
 * the rules between its fields, one save at a time, and what a change means for the shop (roadmap step 4.1).
 * Every test puts the settings back as the migrations left them: the other tests count on them.
 */
class WebV1AdminSettingsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database
    private val original: ShopSettings = StoredSettings.read(db)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        StoredSettings.restore(db, original)
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val admin by lazy { panel.callerWith(Role.ADMIN) }

    private suspend fun ApplicationTestBuilder.read(caller: PanelTestStack.Caller = admin): HttpResponse =
        client.get("$API_V1/admin/settings") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun ApplicationTestBuilder.save(body: JsonObject, caller: PanelTestStack.Caller = admin): HttpResponse =
        client.put("$API_V1/admin/settings") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

    private suspend fun ApplicationTestBuilder.current(): JsonObject = read().also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.json()

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.with(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(this + values)

    private fun JsonObject.without(name: String): JsonObject = JsonObject(this - name)

    /** The list [name] with item [index] changed by [change]. */
    private fun JsonObject.item(name: String, index: Int, change: JsonObject.() -> JsonObject): JsonObject =
        with(name to JsonArray(getValue(name).jsonArray.mapIndexed { i, it -> if (i == index) it.jsonObject.change() else it }))

    /** The item of [name] whose code is [code], changed by [change]. */
    private fun JsonObject.coded(name: String, code: String, change: JsonObject.() -> JsonObject): JsonObject =
        with(name to JsonArray(getValue(name).jsonArray.map { if (it.jsonObject.text("code") == code) it.jsonObject.change() else it }))

    private fun n(value: Number) = JsonPrimitive(value)

    private fun auditRowsOf(caller: PanelTestStack.Caller) = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq caller.userId) and (AuditLogTbl.entityType eq "SETTINGS") }.toList()
    }

    @Test
    fun `GET is the whole document with every field - sent back as it is, it is 200, nothing changes and nothing is logged`() = panel.app {
        val settings = current()

        assertEquals(
            setOf(
                "shippingMethods", "defaultShippingMethod", "freeShippingThreshold", "freeShippingMethods", "reservationEnabled", "reservationHoldDays",
                "returnWindowDays", "lowStockThreshold", "preShipmentFeedbackHours", "paymentPendingMinutes", "nonShippingWeekdays", "supportPhone",
                "supportEmail", "supportHours", "social", "paymentGateways", "siteStats", "features",
            ),
            settings.keys,
        )
        val tipax = settings.getValue("shippingMethods").jsonArray.first().jsonObject
        assertEquals(
            setOf("code", "name", "description", "baseCost", "minDays", "maxDays", "icon", "trackingUrlTemplate", "isActive"),
            tipax.keys,
        )
        assertEquals("تحویل ۱ تا ۲ روز کاری", tipax.text("description"))
        val zarinpal = settings.getValue("paymentGateways").jsonArray.first().jsonObject
        assertEquals(setOf("code", "name", "isActive", "isDefault", "available"), zarinpal.keys)
        assertEquals(listOf("true", "false", "false"), settings.getValue("paymentGateways").jsonArray.map { it.jsonObject.text("available") })
        assertEquals(JsonNull, settings.getValue("siteStats").jsonObject["satisfaction"])
        assertEquals(JsonNull, settings["supportPhone"])
        assertEquals("5", settings.text("lowStockThreshold"))

        val saved = save(settings)

        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(settings, saved.json())
        assertEquals(original.version, StoredSettings.read(db).version)
        assertEquals(emptyList(), auditRowsOf(admin))
    }

    @Test
    fun `every limit of decision 4 is 400 on its own path`() = panel.app {
        val settings = current()
        val body = settings
            .coded("shippingMethods", "TIPAX") { with("baseCost" to n(10_000_001), "minDays" to n(-1), "name" to JsonPrimitive("ا".repeat(51))) }
            .coded("shippingMethods", "POST") { with("maxDays" to n(31), "trackingUrlTemplate" to JsonPrimitive("https://post.ir/track")) }
            .with(
                "freeShippingThreshold" to n(100_000_001),
                "reservationHoldDays" to n(0),
                "returnWindowDays" to n(61),
                "lowStockThreshold" to n(21),
                "preShipmentFeedbackHours" to n(0),
                "paymentPendingMinutes" to n(61),
                "supportPhone" to JsonPrimitive("12345"),
                "supportEmail" to JsonPrimitive("not-an-email"),
                "supportHours" to JsonArray(List(8) { Json.parseToJsonElement("""{"label":"x","weekdays":["FRIDAY"],"from":null,"to":null}""") }),
                "social" to Json.parseToJsonElement("""[{"channel":"TELEGRAM","name":"تلگرام","url":"http://t.me/kiva"}]"""),
                "siteStats" to Json.parseToJsonElement(
                    """{"happyCustomers":-1,"preShipmentPhotos":0,"photoMatchSatisfactionPercent":101,"productModels":0,"satisfaction":null}""",
                ),
            )

        val refused = save(body)

        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(
            listOf(
                "shippingMethods.0.name TEXT_TOO_LONG", "shippingMethods.0.baseCost NUMBER_OUT_OF_RANGE", "shippingMethods.0.minDays NUMBER_OUT_OF_RANGE",
                "shippingMethods.1.maxDays NUMBER_OUT_OF_RANGE", "shippingMethods.1.trackingUrlTemplate TRACKING_URL_TEMPLATE_INVALID",
                "freeShippingThreshold NUMBER_OUT_OF_RANGE", "reservationHoldDays NUMBER_OUT_OF_RANGE", "returnWindowDays NUMBER_OUT_OF_RANGE",
                "lowStockThreshold NUMBER_OUT_OF_RANGE", "preShipmentFeedbackHours NUMBER_OUT_OF_RANGE", "paymentPendingMinutes NUMBER_OUT_OF_RANGE",
                "supportPhone SUPPORT_PHONE_INVALID", "supportEmail EMAIL_INVALID", "supportHours TOO_MANY_ITEMS", "social.0 SOCIAL_LINK_INVALID",
                "siteStats.happyCustomers NUMBER_OUT_OF_RANGE", "siteStats.photoMatchSatisfactionPercent NUMBER_OUT_OF_RANGE",
            ),
            refused.fieldErrors(),
        )
        val range = refused.json().getValue("errors").jsonArray.first { it.jsonObject.text("field") == "lowStockThreshold" }.jsonObject
        assertEquals("این عدد باید بین ۱ و ۲۰ باشه", range.text("message"))
        assertEquals(original.version, StoredSettings.read(db).version)
    }

    @Test
    fun `a top-level field left out is FIELD_REQUIRED, and a support phone sent as null is cleared`() = panel.app {
        val settings = current()

        assertEquals(listOf("supportPhone FIELD_REQUIRED", "features FIELD_REQUIRED"), save(settings.without("supportPhone").without("features")).fieldErrors())

        val withPhone = save(settings.with("supportPhone" to JsonPrimitive("۰۲۱-۹۱۰۰۱۲۳۴")))
        assertEquals("02191001234", withPhone.json().text("supportPhone"))
        val cleared = save(withPhone.json().with("supportPhone" to JsonNull))
        assertEquals(JsonNull, cleared.json()["supportPhone"])
    }

    @Test
    fun `switching the default method off is DEFAULT_SHIPPING_METHOD_INVALID, no active gateway NO_ACTIVE_GATEWAY, COURIER SHIPPING_METHOD_NOT_SUPPORTED`() = panel.app {
        val settings = current()

        val postOff = save(settings.coded("shippingMethods", "POST") { with("isActive" to JsonPrimitive(false)) })
        assertEquals(listOf("defaultShippingMethod DEFAULT_SHIPPING_METHOD_INVALID"), postOff.fieldErrors())

        val noGateway = save(settings.with("paymentGateways" to JsonArray(settings.getValue("paymentGateways").jsonArray.map { it.jsonObject.with("isActive" to JsonPrimitive(false)) })))
        assertEquals(listOf("paymentGateways NO_ACTIVE_GATEWAY", "paymentGateways DEFAULT_GATEWAY_INVALID"), noGateway.fieldErrors())

        val courier = settings.getValue("shippingMethods").jsonArray.first().jsonObject.with("code" to JsonPrimitive("COURIER"))
        val withCourier = save(settings.with("shippingMethods" to JsonArray(settings.getValue("shippingMethods").jsonArray + courier)))
        assertEquals(listOf("shippingMethods.2.code SHIPPING_METHOD_NOT_SUPPORTED"), withCourier.fieldErrors())

        val freeCourier = save(settings.with("freeShippingMethods" to Json.parseToJsonElement("""["POST","COURIER"]""")))
        assertEquals(listOf("freeShippingMethods.1 SHIPPING_METHOD_NOT_SUPPORTED"), freeCourier.fieldErrors())

        val tipaxDefault = save(settings.coded("shippingMethods", "POST") { with("isActive" to JsonPrimitive(false)) }.with("defaultShippingMethod" to JsonPrimitive("TIPAX")))
        assertEquals(HttpStatusCode.OK, tipaxDefault.status, tipaxDefault.bodyAsText())
        assertEquals("TIPAX", tipaxDefault.json().text("defaultShippingMethod"))
    }

    @Test
    fun `an unknown feature, gift wrapping on, a rating with two decimals - each 400 on its path`() = panel.app {
        val settings = current()
        val body = settings.with(
            "features" to Json.parseToJsonElement("""{"wishlistShare":true,"reviewMedia":false,"giftWrap":true,"darkMode":true}"""),
            "siteStats" to Json.parseToJsonElement(
                """{"happyCustomers":12000,"preShipmentPhotos":48000,"photoMatchSatisfactionPercent":98,"productModels":60,
                   "satisfaction":{"averageRating":4.95,"scale":5,"totalCount":2480,"metrics":[{"key":"PHOTO_MATCH","label":"x","percent":98}],"sources":["TELEGRAM"]}}""",
            ),
        )

        assertEquals(
            listOf("siteStats.satisfaction.averageRating RATING_INVALID", "features.darkMode FEATURE_UNKNOWN", "features.giftWrap FEATURE_NOT_AVAILABLE"),
            save(body).fieldErrors(),
        )
    }

    @Test
    fun `the marketing numbers and the contact are saved and given back, the rating with its one decimal`() = panel.app {
        val body = current().with(
            "supportEmail" to JsonPrimitive(" Support@Kiva.ir "),
            "supportHours" to Json.parseToJsonElement("""[{"label":"شنبه تا چهارشنبه","weekdays":["SATURDAY","WEDNESDAY"],"from":"09:00","to":"21:00","messengerOnly":false}]"""),
            "social" to Json.parseToJsonElement("""[{"channel":"INSTAGRAM","name":"اینستاگرام","url":"https://instagram.com/kiva","handle":"@kiva"}]"""),
            "siteStats" to Json.parseToJsonElement(
                """{"happyCustomers":12000,"preShipmentPhotos":48000,"photoMatchSatisfactionPercent":98,"productModels":60,
                   "satisfaction":{"averageRating":4.9,"totalCount":2480,"metrics":[{"key":"SHIPPING_SPEED","percent":91}],"sources":["TELEGRAM","BALE"]}}""",
            ),
            "features" to Json.parseToJsonElement("""{"wishlistShare":true,"reviewMedia":true,"giftWrap":false}"""),
        )

        val saved = save(body)

        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val settings = saved.json()
        assertEquals("support@kiva.ir", settings.text("supportEmail"))
        val satisfaction = settings.getValue("siteStats").jsonObject.getValue("satisfaction").jsonObject
        assertEquals("4.9", satisfaction.getValue("averageRating").jsonPrimitive.content)
        assertEquals("5", satisfaction.text("scale"))
        assertEquals("سرعت ارسال", satisfaction.getValue("metrics").jsonArray.single().jsonObject.text("label"))
        assertEquals("21:00", settings.getValue("supportHours").jsonArray.single().jsonObject.text("to"))
        assertEquals("true", settings.getValue("features").jsonObject.text("wishlistShare"))
        assertEquals(settings, current())
    }

    @Test
    fun `a change logs one SETTINGS_UPDATED row with only the fields that changed`() = panel.app {
        val body = current()
            .coded("shippingMethods", "TIPAX") { with("baseCost" to n(160_000)) }
            .with("lowStockThreshold" to n(3), "freeShippingThreshold" to n(3_500_000))

        assertEquals(HttpStatusCode.OK, save(body).status)

        val row = auditRowsOf(admin).single()
        assertEquals("SETTINGS_UPDATED", row[AuditLogTbl.action])
        assertEquals("shop", row[AuditLogTbl.entityId])
        assertEquals<JsonElement?>(
            Json.parseToJsonElement(
                """[{"field":"shippingMethods.TIPAX.baseCost","from":"145000","to":"160000"},{"field":"freeShippingThreshold","from":"3000000","to":"3500000"},""" +
                    """{"field":"lowStockThreshold","from":"5","to":"3"}]""",
            ),
            row[AuditLogTbl.changes],
        )
        assertEquals(original.version + 1, StoredSettings.read(db).version)
    }

    @Test
    fun `with a threshold of 3 a color with 4 left is no longer low on stock, with 2 it is`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(501))
        val olive = panel.purchase.variantOf("nila-crossbody-bag", "olive")
        setAvailable(olive, 4)
        addToCart(sara, olive)

        assertEquals("LOW_STOCK", cartStockOf(sara))
        assertEquals(HttpStatusCode.OK, save(current().with("lowStockThreshold" to n(3))).status)
        assertEquals("IN_STOCK", cartStockOf(sara))

        setAvailable(olive, 2)
        assertEquals("LOW_STOCK", cartStockOf(sara))
    }

    @Test
    fun `a new free shipping threshold counts from the next cart`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(502))
        addToCart(sara, panel.purchase.variantOf("nila-crossbody-bag", "olive"))
        assertEquals("3000000", cart(sara).getValue("freeShipping").jsonObject.text("threshold"))

        assertEquals(HttpStatusCode.OK, save(current().with("freeShippingThreshold" to n(1_000_000))).status)

        val free = cart(sara).getValue("freeShipping").jsonObject
        assertEquals("1000000", free.text("threshold"))
        assertEquals("true", free.text("eligible"))
    }

    @Test
    fun `48 hours for the photos counts for the next photos only - the ones already sent keep their 24`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(503))
        val mina = panel.caller(PanelTestStack.phone(504))
        val before = panel.paidOrders.paid(sara.user.id, panel.purchase.variantOf("nila-crossbody-bag", "black")).value
        val after = panel.paidOrders.paid(mina.user.id, panel.purchase.variantOf("nila-crossbody-bag", "red")).value
        sendPhotos(before)
        val sentFirst = clock.now

        assertEquals(HttpStatusCode.OK, save(current().with("preShipmentFeedbackHours" to n(48))).status)
        clock.advance(Duration.ofMinutes(5))
        sendPhotos(after)

        assertEquals(sentFirst.plus(Duration.ofHours(24)).toString(), feedbackDeadlineOf(before, sara))
        assertEquals(clock.now.plus(Duration.ofHours(48)).toString(), feedbackDeadlineOf(after, mina))
    }

    @Test
    fun `SUPPORT and OPERATOR are 403, for reading and for saving`() = panel.app {
        val body = current()
        listOf(Role.SUPPORT, Role.OPERATOR).forEach { role ->
            val caller = panel.callerWith(role)
            assertEquals(HttpStatusCode.Forbidden, read(caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, save(body.with("lowStockThreshold" to n(3)), caller).status, "$role")
        }
        assertEquals(5, StoredSettings.read(db).lowStockThreshold)
    }

    @Test
    fun `two saves at the same moment over one version - one is 200, the other 409 CHANGED_BY_SOMEONE_ELSE, and only the first is kept`() {
        // Both requests read the same version before either writes: the second waits for the first's row lock, then finds it gone.
        val bothRead = CountDownLatch(2)
        val repository = object : ShopSettingsRepository by ShopSettingsRepositoryImpl() {
            override fun get(): ShopSettings = ShopSettingsRepositoryImpl().get().also {
                bothRead.countDown()
                bothRead.await(5, TimeUnit.SECONDS)
            }
        }
        val useCases = AdminSettingsUseCases(
            panel.checkPanelAccess,
            GetAdminSettingsUc(panel.transactions, ShopSettingsRepositoryImpl(), panel.purchase.gateways),
            UpdateSettingsUc(panel.transactions, repository, panel.auditTrail, clock),
        )
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(trustedProxyHops = 0)
                configureAuthentication(panel.accessTokens, panel.accountCheck)
                routing { route(API_V1) { webV1AdminSettings(useCases) } }
            }
            val settings = current()
            val statuses = coroutineScope {
                listOf(3, 7).map { threshold -> async { save(settings.with("lowStockThreshold" to n(threshold))).status } }.awaitAll()
            }

            assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.toSet(), "$statuses")
            assertEquals(original.version + 1, StoredSettings.read(db).version)
            assertEquals(1, auditRowsOf(admin).size)
            val kept = listOf(3, 7)[statuses.indexOf(HttpStatusCode.OK)]
            assertEquals(kept, StoredSettings.read(db).lowStockThreshold)
        }
    }

    // --- the shop's side ---

    private suspend fun ApplicationTestBuilder.addToCart(who: PanelTestStack.Caller, variantId: Long) {
        val added = client.post("$API_V1/cart/items") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":1}""")
        }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.cart(who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/cart") { header(HttpHeaders.Authorization, who.bearer) }.json()

    private suspend fun ApplicationTestBuilder.cartStockOf(who: PanelTestStack.Caller): String? =
        cart(who).getValue("items").jsonArray.single().jsonObject.getValue("stock").jsonObject.text("status")

    /** Moves the color's stock so exactly [available] can be sold, the way the panel does (an adjustment). */
    private suspend fun ApplicationTestBuilder.setAvailable(variantId: Long, available: Int) {
        val delta = available - panel.purchase.availableOf(variantId)
        if (delta == 0) return
        val adjusted = client.post("$API_V1/admin/inventory/adjustments") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"delta":$delta,"reason":"STOCKTAKE"}""")
        }
        assertEquals(HttpStatusCode.Created, adjusted.status, adjusted.bodyAsText())
        assertEquals(available, panel.purchase.availableOf(variantId))
    }

    private suspend fun ApplicationTestBuilder.sendPhotos(code: String) {
        val photo = client.post("$API_V1/admin/media") {
            header(HttpHeaders.Authorization, admin.bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "PRE_SHIPMENT")
                        append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json().text("id")
        val drafted = client.put("$API_V1/admin/orders/$code/pre-shipment-media") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"mediaId":"$photo"}]}""")
        }
        assertEquals(HttpStatusCode.OK, drafted.status, drafted.bodyAsText())
        val sent = client.post("$API_V1/admin/orders/$code/pre-shipment-media/send") { header(HttpHeaders.Authorization, admin.bearer) }
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.feedbackDeadlineOf(code: String, buyer: PanelTestStack.Caller): String? =
        client.get("$API_V1/me/orders/$code/media") { header(HttpHeaders.Authorization, buyer.bearer) }.json().text("feedbackDeadline")
}
