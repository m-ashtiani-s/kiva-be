package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import com.kiva.domain.aggregate.settings.model.valueobject.SocialLink
import com.kiva.domain.aggregate.settings.model.valueobject.SupportHours
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.StoredSettings
import com.kiva.infrastructure.persistence.exposed.tbl.StockAlertTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The panel's «موجود شد خبرم کن» queue and «سؤال داری؟» over kiva_test (roadmap step 10.3): the groups in the
 * contract's order with exact stock and no phone, the roles, and the messenger links with their prefilled text.
 */
class WebV1AdminStockAlertsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val catalog = StoredCatalog()
    private val settings = StoredSettings.read(db)
    private var nextAlertId = 9_500_000L

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        StoredSettings.restore(db, settings)
        panel.deleteWhatWasStored()
        catalog.deleteAll()
        seeded.deleteSeeded()
    }

    /** A product in the shop with these colors, each with [stock] bags (by key; missing: none). */
    private fun published(colors: List<String>, stock: Map<String, Int> = emptyMap()): Product {
        val product = catalog.newProduct(colors.map { NewColor(it) }, at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        product.variants.forEach { variant ->
            val bags = stock[variant.colorKey.value] ?: 0
            if (bags > 0) catalog.stock(variant.id) { it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, catalog.staff, clock.now) }
        }
        return product
    }

    private fun Product.variant(key: String): Long = variants.single { it.colorKey.value == key }.id.value

    /** One subscription of [who] for [variantId] of [product] (null: any color), made [hoursAgo] ago. */
    private fun alert(who: PanelTestStack.Caller, product: Product, variantId: Long?, hoursAgo: Long, status: StockAlertStatus = StockAlertStatus.ACTIVE) =
        transaction(db) {
            val at = clock.now.minus(Duration.ofHours(hoursAgo)).atOffset(ZoneOffset.UTC)
            StockAlertTbl.insert {
                it[alertId] = nextAlertId++
                it[userId] = who.user.id.value
                it[productId] = product.id.value
                it[StockAlertTbl.variantId] = variantId
                it[StockAlertTbl.status] = status
                it[createdOn] = at
                if (status == StockAlertStatus.CANCELLED) it[cancelledOn] = at
            }
        }

    private fun customers(n: Int) = (1..n).map { panel.caller(PanelTestStack.phone(910 + it)) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.queue(query: String = "", role: Role = Role.CATALOG_MANAGER): HttpResponse =
        client.get("$API_V1/admin/stock-alerts$query") { header(HttpHeaders.Authorization, panel.callerWith(role).bearer) }

    private fun saveSocial(vararg links: SocialLink, hours: List<SupportHours> = emptyList()) = transaction(db) {
        val repository = ShopSettingsRepositoryImpl()
        val now = repository.get()
        repository.save(
            ShopSettings.constructFromPersisted(
                now.shippingMethods, now.defaultShippingMethod, now.freeShippingThreshold, now.freeShippingMethods, now.nonShippingWeekdays,
                now.paymentPendingMinutes, now.reservationEnabled, now.reservationHoldDays, now.returnWindowDays, now.lowStockThreshold,
                now.preShipmentFeedbackHours, now.paymentGateways, now.supportPhone, now.supportEmail, hours, links.toList(), now.siteStats, now.features,
                now.version,
            ),
        )
    }

    private fun link(channel: SocialChannel, name: String, url: String, handle: String? = "@kiva_shop") =
        SocialLink.constructFromPersisted(channel, name, url, handle)

    @Test
    fun `the queue - most waiting first then the longest waiting - any color with no color and the stock of all - exact stock - no phone anywhere`() = panel.app {
        val people = customers(7)
        val soldOut = published(listOf("lilac", "black"))
        val back = published(listOf("red", "blue", "olive"), stock = mapOf("red" to 2, "blue" to 1))
        people.take(3).forEach { alert(it, soldOut, soldOut.variant("lilac"), hoursAgo = 3) }
        alert(people[3], soldOut, soldOut.variant("lilac"), hoursAgo = 9, status = StockAlertStatus.CANCELLED)
        people.drop(3).take(2).forEach { alert(it, back, null, hoursAgo = 5) }
        people.drop(5).forEach { alert(it, soldOut, soldOut.variant("black"), hoursAgo = 1) }

        val response = queue()

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val raw = response.bodyAsText()
        people.forEach { assertFalse(raw.contains(it.user.phone.value) || raw.contains(it.user.phone.masked()), "a subscriber's phone in the queue") }
        val body = response.json()
        val items = body.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(ContractSchemas.propertiesOf("AdminStockAlertGroup"), items.first().keys)
        assertEquals(
            listOf(
                listOf(soldOut.id.value.toString(), soldOut.variant("lilac").toString(), "lilac", "3", "0", "OUT_OF_STOCK"),
                listOf(back.id.value.toString(), null, null, "2", "3", "LOW_STOCK"),
                listOf(soldOut.id.value.toString(), soldOut.variant("black").toString(), "black", "2", "0", "OUT_OF_STOCK"),
            ),
            items.map {
                listOf(
                    it.getValue("product").jsonObject.text("id"), it.text("variantId"), (it.getValue("color") as? JsonObject)?.text("key"),
                    it.text("waitingCount"), it.text("availableQuantity"), it.text("stockStatus"),
                )
            },
        )
        val any = items[1]
        assertEquals(listOf(null, JsonNull), listOf(any.text("sku"), any.getValue("color")))
        assertTrue(items[0].text("sku")!!.isNotBlank())
        assertEquals(clock.now.minus(Duration.ofHours(3)).toString(), items[0].text("oldestAt"))
        assertEquals("3", body.getValue("meta").jsonObject.text("totalItems"))
    }

    @Test
    fun `inStock=false only the sold-out groups - inStock=true only what is back - productId only its own - inStock=maybe 400 BOOLEAN_INVALID`() = panel.app {
        val people = customers(3)
        val soldOut = published(listOf("lilac", "black"))
        val back = published(listOf("red"), stock = mapOf("red" to 7))
        alert(people[0], soldOut, soldOut.variant("lilac"), hoursAgo = 2)
        alert(people[1], soldOut, null, hoursAgo = 3)
        alert(people[2], back, back.variant("red"), hoursAgo = 4)

        fun ids(body: JsonObject) = body.getValue("items").jsonArray.map { it.jsonObject.getValue("product").jsonObject.text("id") to it.jsonObject.text("variantId") }
        assertEquals(listOf(soldOut.id.value.toString() to null, soldOut.id.value.toString() to soldOut.variant("lilac").toString()), ids(queue("?inStock=false").json()))
        val inStock = queue("?inStock=true").json()
        assertEquals(listOf(back.id.value.toString() to back.variant("red").toString()), ids(inStock))
        assertEquals(listOf("1", "7", "IN_STOCK"), inStock.getValue("items").jsonArray.single().jsonObject.let {
            listOf(inStock.getValue("meta").jsonObject.text("totalItems"), it.text("availableQuantity"), it.text("stockStatus"))
        })
        assertEquals(2, ids(queue("?productId=${soldOut.id.value}").json()).size)

        val bad = queue("?inStock=maybe&productId=abc").json()
        assertEquals(setOf("inStock" to "BOOLEAN_INVALID", "productId" to "PRODUCT_ID_INVALID"),
            bad.getValue("errors").jsonArray.map { it.jsonObject.text("field") to it.jsonObject.text("code") }.toSet())
    }

    @Test
    fun `the queue is ADMIN and CATALOG_MANAGER's - OPERATOR and SUPPORT 403 - no token 401`() = panel.app {
        assertEquals(HttpStatusCode.OK, queue(role = Role.ADMIN).status)
        assertEquals(HttpStatusCode.OK, queue(role = Role.CATALOG_MANAGER).status)
        assertEquals(HttpStatusCode.Forbidden, queue(role = Role.OPERATOR).status)
        assertEquals(HttpStatusCode.Forbidden, queue(role = Role.SUPPORT).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/admin/stock-alerts").status)
    }

    @Test
    fun `«سؤال داری؟» - no color is the default one - only the three messengers in the settings' order - the text in each link, decoded the same`() = panel.app {
        val bag = published(listOf("lilac", "black"))
        val hours = listOf(SupportHours.constructFromPersisted("شنبه تا چهارشنبه", listOf(DayOfWeek.SATURDAY), LocalTime.of(9, 0), LocalTime.of(21, 30), false))
        saveSocial(
            link(SocialChannel.INSTAGRAM, "اینستاگرام", "https://instagram.com/kiva_shop"),
            link(SocialChannel.TELEGRAM, "تلگرام", "https://t.me/kiva_shop"),
            link(SocialChannel.RUBIKA, "روبیکا", "https://rubika.ir/kiva_shop", handle = null),
            hours = hours,
        )

        val response = client.get("$API_V1/products/${bag.id.value}/inquiry")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(ContractSchemas.propertiesOf("ProductInquiry"), body.keys)
        val name = body.text("productName")!!
        val code = body.text("productCode")!!
        assertEquals(bag.sku.value, code)
        val colorName = body.text("colorName")!!
        assertEquals(colorName, client.get("$API_V1/products/${bag.id.value}/inquiry?color=lilac").json().text("colorName"), "the default color")
        val text = "سلام، درباره «$name» رنگ $colorName (کد $code) سؤال دارم. می‌شه عکس/ویدیوی بیشتری بفرستید؟"
        assertEquals(text, body.text("prefilledText"))
        val channels = body.getValue("channels").jsonArray.map { it.jsonObject }
        assertEquals(listOf("TELEGRAM", "RUBIKA"), channels.map { it.text("channel") })
        assertEquals(ContractSchemas.propertiesOf("MessengerLink"), channels.first().keys)
        val telegram = channels[0].text("url")!!
        assertTrue(telegram.startsWith("https://t.me/kiva_shop?text="), telegram)
        assertFalse(telegram.contains(' ') || telegram.contains('+'), "a space is %20")
        assertEquals(text, URLDecoder.decode(telegram.substringAfter("?text="), StandardCharsets.UTF_8))
        assertEquals(listOf("@kiva_shop", null, "پاسخگویی ۹ تا ۲۱:۳۰"), listOf(channels[0].text("handle"), channels[1].text("handle"), channels[0].text("responseHours")))
    }

    @Test
    fun `«سؤال داری؟» - an asked color names it - a color it doesn't come in 400 COLOR_NOT_FOUND - a link with a query gets &text= - a draft 404`() = panel.app {
        val bag = published(listOf("lilac", "black"))
        val draft = catalog.newProduct(listOf(NewColor("lilac")))
        saveSocial(link(SocialChannel.BALE, "بله", "https://ble.ir/join?id=kiva"))

        val black = client.get("$API_V1/products/${bag.id.value}/inquiry?color=black").json()
        val bale = black.getValue("channels").jsonArray.single().jsonObject.text("url")!!
        assertTrue(bale.startsWith("https://ble.ir/join?id=kiva&text="), bale)
        assertEquals(black.text("prefilledText"), URLDecoder.decode(bale.substringAfter("&text="), StandardCharsets.UTF_8))
        assertTrue(black.text("prefilledText")!!.contains("رنگ ${black.text("colorName")} "))
        assertEquals(null, black.getValue("channels").jsonArray.single().jsonObject.text("responseHours"))

        val wrong = client.get("$API_V1/products/${bag.id.value}/inquiry?color=olive")
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(listOf("color", "COLOR_NOT_FOUND"), wrong.json().getValue("errors").jsonArray.single().jsonObject.let { listOf(it.text("field"), it.text("code")) })
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1/products/${draft.id.value}/inquiry").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1/products/abc/inquiry").status)
    }
}
