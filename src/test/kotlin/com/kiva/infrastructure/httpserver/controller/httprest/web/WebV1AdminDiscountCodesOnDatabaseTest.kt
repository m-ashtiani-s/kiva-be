package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.discount.usecase.command.CreateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.command.DeactivateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.command.UpdateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.query.GetDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.query.ListDiscountCodesUc
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.DiscountCodeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountCodeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** Discount codes in the panel (`/admin/discount-codes*`; ADMIN) on kiva_test, and what they do in the shop (step 4.2). */
class WebV1AdminDiscountCodesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val admin by lazy { panel.callerWith(Role.ADMIN) }

    private suspend fun ApplicationTestBuilder.create(body: String, caller: PanelTestStack.Caller = admin): HttpResponse =
        client.post("$API_V1/admin/discount-codes") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.update(id: Any, body: String, caller: PanelTestStack.Caller = admin): HttpResponse =
        client.put("$API_V1/admin/discount-codes/$id") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.deactivate(id: Any, caller: PanelTestStack.Caller = admin): HttpResponse =
        client.delete("$API_V1/admin/discount-codes/$id") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun ApplicationTestBuilder.list(query: String = "", caller: PanelTestStack.Caller = admin): HttpResponse =
        client.get("$API_V1/admin/discount-codes$query") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private val paeez = """{"code":" paeez۱۵ ","type":"PERCENT","value":15,"label":"۱۵٪ تخفیف پاییزه","minSubtotal":2000000}"""

    private fun auditOf(id: Long): List<Pair<String, JsonElement?>> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "DISCOUNT_CODE") and (AuditLogTbl.entityId eq id.toString()) }
            .orderBy(AuditLogTbl.id).map { it[AuditLogTbl.action] to it[AuditLogTbl.changes] }
    }

    @Test
    fun `a code made with its text normalised is 201 with every field - the same text again is 409 DUPLICATE_CODE`() = panel.app {
        val made = create(paeez)

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val code = made.json()
        assertEquals("PAEEZ15", code.text("code"))
        assertEquals(
            setOf(
                "id", "code", "type", "value", "label", "minSubtotal", "maxDiscount", "startsAt", "endsAt", "usageLimit", "perUserLimit", "firstOrderOnly",
                "requiresLogin", "excludeSaleItems", "categoryIds", "productIds", "isActive", "usedCount", "totalDiscountGiven", "createdAt", "updatedAt",
            ),
            code.keys,
        )
        assertEquals(JsonNull, code["perUserLimit"])
        assertEquals("true", code.text("isActive"))
        assertEquals("0", code.text("usedCount"))
        assertEquals(clock.now.toString(), code.text("createdAt"))
        assertEquals(listOf("DISCOUNT_CODE_CREATED" to null), auditOf(code.getValue("id").jsonPrimitive.long))

        val again = create("""{"code":"PAEEZ15","type":"FIXED","value":10000,"label":"x"}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("DUPLICATE_CODE", again.json().text("code"))
    }

    @Test
    fun `PERCENT 120, FIXED 500 and FIXED with a cap are 400, each with its own code`() = panel.app {
        assertEquals(listOf("value DISCOUNT_VALUE_INVALID"), create("""{"code":"BIG","type":"PERCENT","value":120,"label":"x"}""").fieldErrors())
        assertEquals(listOf("value DISCOUNT_VALUE_INVALID"), create("""{"code":"TINY","type":"FIXED","value":500,"label":"x"}""").fieldErrors())
        assertEquals(
            listOf("maxDiscount MAX_DISCOUNT_NOT_ALLOWED"),
            create("""{"code":"CAPPED","type":"FIXED","value":150000,"maxDiscount":50000,"label":"x"}""").fieldErrors(),
        )
        assertEquals(
            listOf("code DISCOUNT_CODE_TEXT_INVALID", "type DISCOUNT_TYPE_INVALID", "label FIELD_REQUIRED", "endsAt DATE_TIME_INVALID"),
            create("""{"code":"AB","type":"HALF","value":10,"endsAt":"tomorrow"}""").fieldErrors(),
        )
        assertEquals(0, transaction(db) { DiscountCodeTbl.selectAll().count() })
    }

    @Test
    fun `first orders only without a login asked for is stored as needing a login`() = panel.app {
        val made = create("""{"code":"WELCOME2","type":"FIXED","value":150000,"label":"x","firstOrderOnly":true,"requiresLogin":false}""")

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        assertEquals("true", made.json().text("requiresLogin"))
    }

    @Test
    fun `a category or a product that isn't there is 400 on its index`() = panel.app {
        val shoulder = transaction(db) { CategoryRepositoryImpl().findAll().first().id.value }
        val product = transaction(db) { ProductQmTbl.selectAll().first()[ProductQmTbl.productId] }

        assertEquals(
            listOf("categoryIds.0 CATEGORY_NOT_FOUND", "productIds.1 PRODUCT_NOT_FOUND"),
            create("""{"code":"CAT10","type":"PERCENT","value":10,"label":"x","categoryIds":[999999,$shoulder],"productIds":[$product,999999]}""")
                .fieldErrors(),
        )
        val made = create("""{"code":"CAT10","type":"PERCENT","value":10,"label":"x","categoryIds":[$shoulder]}""")
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        assertEquals("[$shoulder]", made.json()["categoryIds"].toString())
    }

    @Test
    fun `a code made in the panel works in the cart, and once switched off the cart drops it at its next sync`() = panel.app {
        val id = create("""{"code":"PANEL10","type":"PERCENT","value":10,"label":"۱۰٪ پنل"}""").json().getValue("id").jsonPrimitive.long
        val sara = panel.caller(PanelTestStack.phone(601))
        addToCart(sara, panel.purchase.variantOf("nila-crossbody-bag", "olive"))

        val applied = client.post("$API_V1/cart/discount-code") {
            header(HttpHeaders.Authorization, sara.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"panel10"}""")
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals("PANEL10", cart(sara).getValue("discount").jsonObject.text("code"))

        assertEquals(HttpStatusCode.NoContent, deactivate(id).status)

        val synced = cart(sara)
        assertEquals(JsonNull, synced["discount"])
        assertEquals(listOf("DISCOUNT_REMOVED"), synced.getValue("issues").jsonArray.map { it.jsonObject.text("code") })
    }

    @Test
    fun `usedCount and totalDiscountGiven count only paid orders not given back - a cancelled one leaves the count`() = panel.app {
        val id = create("""{"code":"COUNT10","type":"PERCENT","value":10,"label":"x"}""").json().getValue("id").jsonPrimitive.long
        val olive = panel.purchase.variantOf("nila-crossbody-bag", "olive")
        val kept = panel.paidOrders.paid(panel.caller(PanelTestStack.phone(602)).user.id, olive, discountCode = "COUNT10")
        val cancelled = panel.paidOrders.paid(panel.caller(PanelTestStack.phone(603)).user.id, olive, discountCode = "COUNT10")
        panel.paidOrders.place(panel.caller(PanelTestStack.phone(604)).user.id, olive, discountCode = "COUNT10")

        val cancel = client.post("$API_V1/admin/orders/${cancelled.value}/status") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"CANCELLED","notifyCustomer":false}""")
        }
        assertEquals(HttpStatusCode.OK, cancel.status, cancel.bodyAsText())

        val listed = list().json().getValue("items").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.long == id }.jsonObject
        assertEquals("1", listed.text("usedCount"))
        assertEquals("145000", listed.text("totalDiscountGiven"), "10% of the one kept order, ${kept.value}")
    }

    @Test
    fun `the list is newest first, its active filter is the switch, and anything else for active is 400`() = panel.app {
        val first = create("""{"code":"FIRST","type":"PERCENT","value":10,"label":"x"}""").json().getValue("id").jsonPrimitive.long
        clock.advance(java.time.Duration.ofMinutes(1))
        val second = create("""{"code":"SECOND","type":"PERCENT","value":10,"label":"x","isActive":false}""").json().getValue("id").jsonPrimitive.long

        assertEquals(listOf(second, first), list().json().getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        assertEquals(listOf(first), list("?active=true").json().getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        assertEquals(listOf(second), list("?active=false").json().getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long })
        assertEquals(listOf("active BOOLEAN_INVALID"), list("?active=yes").fieldErrors())
    }

    @Test
    fun `an edit replaces the terms and logs what changed - the same again logs nothing, another code's text is 409, a bad id 404`() = panel.app {
        val id = create(paeez).json().getValue("id").jsonPrimitive.long
        create("""{"code":"OTHER","type":"PERCENT","value":10,"label":"x"}""")

        val edited = update(id, """{"code":"PAEEZ15","type":"PERCENT","value":20,"label":"۲۰٪ تخفیف پاییزه","maxDiscount":300000}""")
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertEquals("20", edited.json().text("value"))
        assertEquals(JsonNull, edited.json()["minSubtotal"])
        assertEquals(HttpStatusCode.OK, update(id, """{"code":"PAEEZ15","type":"PERCENT","value":20,"label":"۲۰٪ تخفیف پاییزه","maxDiscount":300000}""").status)

        val rows = auditOf(id)
        assertEquals(listOf("DISCOUNT_CODE_CREATED", "DISCOUNT_CODE_UPDATED"), rows.map { it.first })
        assertEquals(
            setOf("value", "label", "minSubtotal", "maxDiscount"),
            rows.last().second!!.jsonArray.map { it.jsonObject.text("field") }.toSet(),
        )
        assertEquals("DUPLICATE_CODE", update(id, """{"code":"other","type":"PERCENT","value":10,"label":"x"}""").json().text("code"))
        assertEquals(HttpStatusCode.NotFound, update("abc", paeez).status)
        assertEquals(HttpStatusCode.NotFound, update(999999, paeez).status)
        assertEquals(HttpStatusCode.NotFound, deactivate("abc").status)
    }

    @Test
    fun `an older 2-letter code keeps its own text when edited - another 2-letter text is refused`() = panel.app {
        val id = create("""{"code":"OLD","type":"PERCENT","value":10,"label":"x"}""").json().getValue("id").jsonPrimitive.long
        transaction(db) { DiscountCodeTbl.update({ DiscountCodeTbl.id eq id }) { it[code] = "OL" } }

        assertEquals(HttpStatusCode.OK, update(id, """{"code":"OL","type":"PERCENT","value":12,"label":"x"}""").status)
        assertEquals(listOf("code DISCOUNT_CODE_TEXT_INVALID"), update(id, """{"code":"XY","type":"PERCENT","value":12,"label":"x"}""").fieldErrors())
    }

    @Test
    fun `switching off twice is 204 both times, with one log row`() = panel.app {
        val id = create(paeez).json().getValue("id").jsonPrimitive.long

        assertEquals(HttpStatusCode.NoContent, deactivate(id).status)
        assertEquals(HttpStatusCode.NoContent, deactivate(id).status)

        assertEquals(listOf("DISCOUNT_CODE_CREATED", "DISCOUNT_CODE_DEACTIVATED"), auditOf(id).map { it.first })
        assertEquals(false, transaction(db) { DiscountCodeTbl.selectAll().where { DiscountCodeTbl.id eq id }.single()[DiscountCodeTbl.isActive] })
    }

    @Test
    fun `OPERATOR and CONTENT_EDITOR are 403 on every discount code endpoint`() = panel.app {
        listOf(Role.OPERATOR, Role.CONTENT_EDITOR).forEach { role ->
            val caller = panel.callerWith(role)
            assertEquals(HttpStatusCode.Forbidden, list(caller = caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, create(paeez, caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, update(1, paeez, caller).status, "$role")
            assertEquals(HttpStatusCode.Forbidden, deactivate(1, caller).status, "$role")
        }
        assertEquals(0, transaction(db) { DiscountCodeTbl.selectAll().count() })
    }

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

    @Test
    fun `two codes with one text at the same moment, 10 times - one 201, the other 409 DUPLICATE_CODE, never a 500, one row`() {
        repeat(10) { round ->
            // Both requests look the text up before either stores it, so the unique key is the one that refuses the second.
            val bothLooked = CountDownLatch(2)
            val codes = object : DiscountCodeRepository by DiscountCodeRepositoryImpl() {
                override fun findByCode(code: DiscountCodeText): DiscountCode? = DiscountCodeRepositoryImpl().findByCode(code).also {
                    bothLooked.countDown()
                    bothLooked.await(5, TimeUnit.SECONDS)
                }
            }
            val useCases = AdminDiscountCodeUseCases(
                panel.checkPanelAccess,
                ListDiscountCodesUc(panel.transactions, panel.discountCodes),
                GetDiscountCodeUc(panel.transactions, panel.discountCodes),
                CreateDiscountCodeUc(panel.transactions, codes, panel.discountReferences, panel.auditTrail, clock),
                UpdateDiscountCodeUc(panel.transactions, panel.discountCodes, panel.discountReferences, panel.auditTrail, clock),
                DeactivateDiscountCodeUc(panel.transactions, panel.discountCodes, panel.auditTrail, clock),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application {
                    configureHttp(trustedProxyHops = 0)
                    configureAuthentication(panel.accessTokens, panel.accountCheck)
                    routing { route(API_V1) { webV1AdminDiscountCodes(useCases) } }
                }
                val text = "TWIN$round"

                val statuses = coroutineScope {
                    List(2) { async { create("""{"code":"$text","type":"FIXED","value":10000,"label":"x"}""").status } }.awaitAll()
                }

                assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.Conflict), statuses.toSet(), "round $round: $statuses")
                assertEquals(1L, transaction(db) { DiscountCodeTbl.selectAll().where { DiscountCodeTbl.code eq text }.count() })
            }
        }
    }
}
