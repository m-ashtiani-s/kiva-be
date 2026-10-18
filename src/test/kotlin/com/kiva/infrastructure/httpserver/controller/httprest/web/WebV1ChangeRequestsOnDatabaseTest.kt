package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.product.usecase.command.DeactivateVariantUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The customer's change request after the photos (phase 3 step 3.3) over the real stack on kiva_test:
 * `POST /me/orders/{orderCode}/media-feedback` with REQUEST_CHANGE, its shape, its order of rules and what it changes.
 */
class WebV1ChangeRequestsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
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

    private val nila by lazy { listOf("black", "olive", "red", "cream", "gray", "pink", "blue").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val mahak by lazy { listOf("black", "lilac", "cream", "brown").map { panel.purchase.variantOf("mahak-shoulder-bag", it) } }

    private fun operator() = panel.caller(PanelTestStack.phone(90), Role.OPERATOR)

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** Every (field, code) of a 400. */
    private suspend fun HttpResponse.problems(): Set<Pair<String?, String?>> {
        assertEquals(HttpStatusCode.BadRequest, status, bodyAsText())
        return json()["errors"]!!.jsonArray.map { it.jsonObject.text("field") to it.jsonObject.text("code") }.toSet()
    }

    /** One photo drafted and sent by the operator: PHOTO_SENT with its photos SENT. */
    private suspend fun ApplicationTestBuilder.photographed(code: OrderCode) {
        val staff = operator()
        val photo = client.post("$API_V1/admin/media") {
            header(HttpHeaders.Authorization, staff.bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "PRE_SHIPMENT")
                        append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }.json().text("id")
        val set = client.put("$API_V1/admin/orders/${code.value}/pre-shipment-media") {
            header(HttpHeaders.Authorization, staff.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":[{"mediaId":"$photo"}]}""")
        }
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        val sent = client.post("$API_V1/admin/orders/${code.value}/pre-shipment-media/send") { header(HttpHeaders.Authorization, staff.bearer) }
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.feedback(code: OrderCode, who: PanelTestStack.Caller, body: String): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/media-feedback") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A paid Nila (its black) of [who], its photo sent. */
    private suspend fun ApplicationTestBuilder.photoSent(who: PanelTestStack.Caller): OrderCode =
        panel.paidOrders.paid(who.user.id, nila[0]).also { photographed(it) }

    private fun colorRequest(desired: Long, type: String = "COLOR", line: Int = 1, note: String? = null) =
        """{"decision":"REQUEST_CHANGE","changeType":"$type","orderItemId":$line,"desiredVariantId":$desired${note?.let { ",\"note\":\"$it\"" }.orEmpty()}}"""

    @Test
    fun `asked - CHANGE_REQUESTED, its photos too, the request on the page as asked, no cancel, no second request, one entry, nothing in the change log`() = panel.app {
        val sara = customer(1)
        val code = photoSent(sara)

        val asked = feedback(code, sara, colorRequest(nila[1], note = "زیتونیش رو می‌خوام"))

        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
        val page = asked.json()
        assertEquals("CHANGE_REQUESTED", page.text("status"))
        assertEquals("درخواست تغییر ثبت شد", page.text("statusLabel"))
        val media = page["preShipmentMediaDetail"]!!.jsonObject
        assertEquals("CHANGE_REQUESTED", media.text("status"))
        val answer = media["feedback"]!!.jsonObject
        assertEquals(
            listOf("REQUEST_CHANGE", "COLOR", "1", nila[1].toString(), "زیتونیش رو می‌خوام", clock.now.toString()),
            listOf(answer.text("decision"), answer.text("changeType"), answer.text("orderItemId"), answer.text("desiredVariantId"), answer.text("note"), answer.text("at")),
        )
        assertNull(answer.text("resolution"))
        val actions = page["actions"]!!.jsonObject
        assertEquals(listOf(false, false, false), listOf("canCancel", "canRequestChange", "canApproveMedia").map { actions[it]!!.jsonPrimitive.boolean })
        assertEquals("درخواست تغییر ثبت شد", page["timeline"]!!.jsonArray.last().jsonObject.text("label"))
        val again = feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"OTHER","note":"یه چیز دیگه"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
        assertEquals("CHANGE_REQUEST_NOT_ALLOWED", again.json().text("code"))
        val logged = transaction(db) { AuditLogTbl.selectAll().where { AuditLogTbl.entityId eq code.value }.map { it[AuditLogTbl.action] } }
        assertEquals(listOf("ORDER_MEDIA_SET", "ORDER_PHOTO_SENT"), logged, "the customer's request is not the panel's")
    }

    @Test
    fun `after approving the photos it is still taken - before any photo it is 422 CHANGE_REQUEST_NOT_ALLOWED`() = panel.app {
        val sara = customer(2)
        val approved = photoSent(sara)
        assertEquals(HttpStatusCode.OK, feedback(approved, sara, """{"decision":"APPROVE"}""").status)
        val notYet = panel.paidOrders.paid(sara.user.id, nila[2])

        assertEquals(HttpStatusCode.OK, feedback(approved, sara, colorRequest(nila[1])).status)
        val early = feedback(notYet, sara, colorRequest(nila[1]))

        assertEquals(HttpStatusCode.UnprocessableEntity, early.status)
        assertEquals("CHANGE_REQUEST_NOT_ALLOWED", early.json().text("code"))
    }

    @Test
    fun `the shape - every problem at once, on its field, and nothing changed`() = panel.app {
        val sara = customer(3)
        val code = photoSent(sara)
        val long = "ب".repeat(501)

        assertEquals(setOf("changeType" to "CHANGE_TYPE_REQUIRED"), feedback(code, sara, """{"decision":"REQUEST_CHANGE"}""").problems())
        assertEquals(setOf("changeType" to "CHANGE_TYPE_INVALID"), feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"SIZE"}""").problems())
        assertEquals(
            setOf("orderItemId" to "ORDER_ITEM_REQUIRED", "desiredVariantId" to "DESIRED_VARIANT_REQUIRED"),
            feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"COLOR"}""").problems(),
        )
        assertEquals(
            setOf("orderItemId" to "ORDER_ITEM_REQUIRED", "desiredVariantId" to "DESIRED_VARIANT_REQUIRED"),
            feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"MODEL"}""").problems(),
        )
        assertEquals(setOf("orderItemId" to "ORDER_ITEM_REQUIRED"), feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"CANCEL_ITEM"}""").problems())
        assertEquals(setOf("note" to "NOTE_REQUIRED"), feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"OTHER","note":"  "}""").problems())
        assertEquals(
            setOf("note" to "STATUS_NOTE_TOO_LONG", "orderItemId" to "ORDER_ITEM_NOT_FOUND", "desiredVariantId" to "DESIRED_VARIANT_INVALID"),
            feedback(code, sara, """{"decision":"REQUEST_CHANGE","changeType":"COLOR","orderItemId":0,"desiredVariantId":-4,"note":"$long"}""").problems(),
        )
        assertEquals(setOf("decision" to "FEEDBACK_DECISION_INVALID"), feedback(code, sara, """{"decision":"MAYBE","changeType":"COLOR"}""").problems())
        assertEquals("PHOTO_SENT", feedback(code, sara, """{"decision":"APPROVE","changeType":"SIZE"}""").json().text("status"), "APPROVE ignores the change's fields")
    }

    @Test
    fun `colors that don't fit - another product's for COLOR, the line's own, the same product's for MODEL, one nobody has - are 400 DESIRED_VARIANT_INVALID`() = panel.app {
        val sara = customer(4)
        val code = photoSent(sara)

        listOf(colorRequest(mahak[0]), colorRequest(nila[0]), colorRequest(nila[1], type = "MODEL"), colorRequest(987_654_321)).forEach { body ->
            assertEquals(setOf("desiredVariantId" to "DESIRED_VARIANT_INVALID"), feedback(code, sara, body).problems(), body)
        }
        assertEquals(setOf("orderItemId" to "ORDER_ITEM_NOT_FOUND"), feedback(code, sara, colorRequest(nila[1], line = 2)).problems())
        assertEquals(HttpStatusCode.OK, feedback(code, sara, colorRequest(mahak[0], type = "MODEL")).status, "MODEL with another bag is taken")
    }

    @Test
    fun `a color switched off is 422 PRODUCT_UNAVAILABLE, and the order's stays as it was`() = panel.app {
        val sara = customer(5)
        val code = photoSent(sara)
        val productId = transaction(db) { ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq nila[3] }.single()[ProductVariantQmTbl.productId] }
        val admin = panel.callerWith(Role.ADMIN)
        runBlocking { panel.adminProducts.deactivateVariant.execute(DeactivateVariantUc.Cmd(productId.toString(), nila[3].toString(), PanelActor(UserId.constructFromPersisted(admin.userId), null))) }
            .getOrNull() ?: error("not switched off")

        val refused = feedback(code, sara, colorRequest(nila[3]))

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("PRODUCT_UNAVAILABLE", refused.json().text("code"))
        assertEquals(HttpStatusCode.OK, feedback(code, sara, """{"decision":"APPROVE"}""").status, "still waiting for an answer")
    }

    @Test
    fun `someone else's order is 404, whatever they ask`() = panel.app {
        val sara = customer(6)
        val code = photoSent(sara)

        assertEquals(HttpStatusCode.NotFound, feedback(code, customer(7), colorRequest(nila[1])).status)
        assertEquals(HttpStatusCode.NotFound, feedback(OrderCode.constructFromPersisted("KV-000000"), sara, colorRequest(nila[1])).status)
    }

    @Test
    fun `OTHER and CANCEL_ITEM are kept as asked - the fields their type doesn't take are left out`() = panel.app {
        val sara = customer(8)
        val other = photoSent(sara)
        val cancel = photoSent(sara)

        val otherPage = feedback(other, sara, """{"decision":"REQUEST_CHANGE","changeType":"OTHER","orderItemId":1,"desiredVariantId":${nila[1]},"note":"بند بلندتر"}""").json()
        val cancelPage = feedback(cancel, sara, """{"decision":"REQUEST_CHANGE","changeType":"CANCEL_ITEM","orderItemId":1,"desiredVariantId":${nila[1]}}""").json()

        val otherAnswer = otherPage["preShipmentMediaDetail"]!!.jsonObject["feedback"]!!.jsonObject
        assertEquals(listOf("OTHER", null, null, "بند بلندتر"), listOf(otherAnswer.text("changeType"), otherAnswer.text("orderItemId"), otherAnswer.text("desiredVariantId"), otherAnswer.text("note")))
        val cancelAnswer = cancelPage["preShipmentMediaDetail"]!!.jsonObject["feedback"]!!.jsonObject
        assertEquals(listOf("CANCEL_ITEM", "1", null), listOf(cancelAnswer.text("changeType"), cancelAnswer.text("orderItemId"), cancelAnswer.text("desiredVariantId")))
    }
}
