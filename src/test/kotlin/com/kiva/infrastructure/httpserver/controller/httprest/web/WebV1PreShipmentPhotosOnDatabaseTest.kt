package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.order.usecase.command.ApproveSilentPhotosUc
import com.kiva.application.usecase.order.usecase.query.FindSilentPhotoOrdersUc
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The photos before shipping (phase 2 step 2.5) over the real stack on kiva_test: the operator's draft, sending it by
 * hand, the customer's «همونه!», and the job approving the photos nobody answered.
 */
class WebV1PreShipmentPhotosOnDatabaseTest {

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

    private val nila by lazy { listOf("black", "olive", "red").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val operator by lazy { panel.callerWith(Role.OPERATOR) }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(300 + n))

    private suspend fun ApplicationTestBuilder.upload(purpose: String = "PRE_SHIPMENT", video: Boolean = false): String =
        client.post("$API_V1/admin/media") {
            header(HttpHeaders.Authorization, operator.bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", purpose)
                        val (bytes, name) = if (video) FakeMediaProcessor.video() to "v.mp4" else FakeMediaProcessor.photo(2000, 1500) to "a.jpg"
                        append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
                    },
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json().getValue("id").jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.setPhotos(code: OrderCode, items: String, caller: PanelTestStack.Caller = operator): HttpResponse =
        client.put("$API_V1/admin/orders/${code.value}/pre-shipment-media") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":$items}""")
        }

    private suspend fun ApplicationTestBuilder.send(code: OrderCode, body: String? = null): HttpResponse =
        client.post("$API_V1/admin/orders/${code.value}/pre-shipment-media/send") {
            header(HttpHeaders.Authorization, operator.bearer)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.answer(code: OrderCode, who: PanelTestStack.Caller, decision: String = "APPROVE"): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/media-feedback") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"$decision"}""")
        }

    private suspend fun ApplicationTestBuilder.get(path: String, who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, who.bearer) }.json()

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.array(name: String): JsonArray = this[name]!!.jsonArray

    private fun item(mediaId: String, line: Int? = null, view: String? = null) =
        """{"mediaId":"$mediaId"${line?.let { ",\"orderItemId\":$it" }.orEmpty()}${view?.let { ",\"view\":\"$it\"" }.orEmpty()}}"""

    /** A paid order of one bag, its photo drafted and sent; the photo's id. */
    private suspend fun ApplicationTestBuilder.photographed(code: OrderCode): String {
        val photo = upload()
        assertEquals(HttpStatusCode.OK, setPhotos(code, "[${item(photo)}]").status)
        assertEquals(HttpStatusCode.OK, send(code).status)
        return photo
    }

    @Test
    fun `the draft is replaced twice and the customer sees nothing - sent, it is PHOTO_SENT, the customer sees the batch, and an SMS goes`() = panel.app {
        val sara = customer(1)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        val first = upload()
        val second = upload()
        val video = upload(video = true)

        assertEquals(HttpStatusCode.OK, setPhotos(code, "[${item(first, 1, "FRONT")}]").status)
        val replaced = setPhotos(code, "[${item(second, 1, "INTERIOR")},${item(video)}]")
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
        assertEquals(listOf(second, video), replaced.json().array("items").map { it.jsonObject.text("id") })

        val before = get("/me/orders/${code.value}/media", sara)
        assertEquals("WAITING", before.text("status"))
        assertEquals(emptyList(), before.array("items"))
        assertTrue(before.text("waitingMessage")!!.isNotBlank())
        assertEquals("@sara_m", before.text("username"))
        assertEquals(emptyList(), get("/me/orders/${code.value}", sara)["preShipmentMediaDetail"]!!.jsonObject.array("items"))

        clock.advance(Duration.ofMinutes(10))
        val sent = send(code, """{"manual":true,"message":"این کیف خودته"}""")

        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        val order = sent.json()
        assertEquals("PHOTO_SENT", order.text("status"))
        assertEquals(emptyList(), order.array("draftItems"))
        val media = get("/me/orders/${code.value}/media", sara)
        assertEquals("SENT", media.text("status"))
        assertEquals(listOf(second, video), media.array("items").map { it.jsonObject.text("id") })
        assertEquals("INTERIOR", media.array("items").first().jsonObject.text("view"))
        assertEquals(1, media.array("items").first().jsonObject["orderItemId"]!!.jsonPrimitive.int)
        assertNull(media.text("waitingMessage"))
        assertEquals(clock.now.plus(Duration.ofHours(24)).toString(), media.text("feedbackDeadline"))
        val card = get("/me/orders/${code.value}", sara)
        assertEquals(2, card["preShipmentMedia"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertTrue(card["preShipmentMedia"]!!.jsonObject["hasVideo"]!!.jsonPrimitive.boolean)
        assertTrue(card["actions"]!!.jsonObject["canApproveMedia"]!!.jsonPrimitive.boolean)
        assertEquals("عکس کیفت فرستاده شد", card.array("timeline").last().jsonObject.text("label"))
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.photosSent.toList())
    }

    @Test
    fun `an order of two lines with photos of the first only is 422 PHOTOS_INCOMPLETE naming the second`() = panel.app {
        val code = panel.paidOrders.paid(customer(2).user.id, nila[0], nila[1])
        setPhotos(code, "[${item(upload(), 1)},${item(upload())}]")

        val refused = send(code)

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("PHOTOS_INCOMPLETE", refused.json().text("code"))
        assertEquals(listOf(2), refused.json()["meta"]!!.jsonObject.array("missingItemIds").map { it.jsonPrimitive.int })
    }

    @Test
    fun `a product's photo, a missing upload, a line the order hasn't, a photo twice and too many are 400 on their fields`() = panel.app {
        val code = panel.paidOrders.paid(customer(3).user.id, nila[0])
        val product = upload("PRODUCT")
        val photo = upload()

        fun errorsOf(json: JsonObject) = json.array("errors").map { it.jsonObject.text("field") to it.jsonObject.text("code") }

        assertEquals(listOf("items.0.mediaId" to "MEDIA_PURPOSE_MISMATCH"), errorsOf(setPhotos(code, "[${item(product)}]").json()))
        assertEquals(listOf("items.0.mediaId" to "MEDIA_NOT_FOUND"), errorsOf(setPhotos(code, "[${item("med_0000000000000000000099")}]").json()))
        assertEquals(listOf("items.0.orderItemId" to "ORDER_ITEM_NOT_FOUND"), errorsOf(setPhotos(code, "[${item(photo, 5)}]").json()))
        assertEquals(listOf("items.1.mediaId" to "MEDIA_DUPLICATE"), errorsOf(setPhotos(code, "[${item(photo)},${item(photo)}]").json()))
        assertEquals(listOf("items" to "PHOTOS_REQUIRED"), errorsOf(setPhotos(code, "[]").json()))
        assertEquals(listOf("items" to "PHOTOS_TOO_MANY"), errorsOf(setPhotos(code, (1..21).joinToString(",", "[", "]") { item(photo) }).json()))
        assertEquals(listOf("items.0.view" to "MEDIA_VIEW_INVALID"), errorsOf(setPhotos(code, "[${item(photo, view = "TOP")}]").json()))
    }

    @Test
    fun `manual false is 400 MANUAL_ONLY, and a draft on a held order is 422`() = panel.app {
        val sara = customer(4)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        setPhotos(code, "[${item(upload())}]")

        val manual = send(code, """{"manual":false}""")
        assertEquals(HttpStatusCode.BadRequest, manual.status)
        assertEquals("manual" to "MANUAL_ONLY", manual.json().array("errors").single().jsonObject.let { it.text("field") to it.text("code") })

        val home = panel.paidOrders.savedAddress(sara.user.id)
        val held = panel.paidOrders.paid(sara.user.id, nila[1], reserve = true, addressId = home)
        val refused = setPhotos(held, "[${item(upload())}]")
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("INVALID_STATUS_TRANSITION", refused.json().text("code"))
    }

    @Test
    fun `the customer approves - APPROVED, the button gone - approving again is 200, someone else is 404, a change request 422`() = panel.app {
        val sara = customer(5)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        photographed(code)

        val approved = answer(code, sara)
        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val page = approved.json()
        assertEquals("APPROVED", page["preShipmentMediaDetail"]!!.jsonObject.text("status"))
        assertEquals("APPROVE", page["preShipmentMediaDetail"]!!.jsonObject["feedback"]!!.jsonObject.text("decision"))
        assertEquals(false, page["actions"]!!.jsonObject["canApproveMedia"]!!.jsonPrimitive.boolean)
        assertEquals("PHOTO_SENT", page.text("status"))
        assertEquals("عکس رو تأیید کردی", page.array("timeline").last().jsonObject.text("label"))

        assertEquals(HttpStatusCode.OK, answer(code, sara).status)
        assertEquals(HttpStatusCode.NotFound, answer(code, customer(6)).status)
        val change = answer(code, sara, "REQUEST_CHANGE")
        assertEquals(HttpStatusCode.UnprocessableEntity, change.status)
        assertEquals("CHANGE_REQUEST_NOT_ALLOWED", change.json().text("code"))
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1/me/orders/${code.value}/media") { header(HttpHeaders.Authorization, customer(7).bearer) }.status)
    }

    @Test
    fun `nothing to approve before the photos are sent is 422`() = panel.app {
        val sara = customer(8)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])

        val refused = answer(code, sara)

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("INVALID_STATUS_TRANSITION", refused.json().text("code"))
    }

    @Test
    fun `the job approves the photos nobody answered 24 hours and a minute on - at 23 hours it leaves them`() = panel.app {
        val sara = customer(9)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        photographed(code)

        clock.advance(Duration.ofHours(23))
        runBlocking { panel.findSilentPhotos.execute(FindSilentPhotoOrdersUc.Qry) }.getOrNull()!!
            .forEach { runBlocking { panel.approveSilentPhotos.execute(ApproveSilentPhotosUc.Cmd(it)) } }
        // A day on, the morning's access token is over: the customer logs in again.
        assertEquals("SENT", get("/me/orders/${code.value}/media", customer(9)).text("status"))

        clock.advance(Duration.ofMinutes(61))
        val silent = runBlocking { panel.findSilentPhotos.execute(FindSilentPhotoOrdersUc.Qry) }.getOrNull()!!
        silent.forEach { runBlocking { panel.approveSilentPhotos.execute(ApproveSilentPhotosUc.Cmd(it)) } }

        val page = get("/me/orders/${code.value}", customer(9))
        assertEquals("APPROVED", page["preShipmentMediaDetail"]!!.jsonObject.text("status"))
        assertEquals(JsonNull, page["preShipmentMediaDetail"]!!.jsonObject["feedback"])
        assertEquals("خودکار تأیید شد", page.array("timeline").last().jsonObject.text("label"))
    }

    @Test
    fun `the change log has the photos set with their counts per line, and their sending - a CATALOG_MANAGER can't do either`() = panel.app {
        val code = panel.paidOrders.paid(customer(10).user.id, nila[0])
        val photo = upload()
        setPhotos(code, "[${item(photo, 1)},${item(upload())}]")
        send(code)

        val rows = transaction(db) {
            AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq operator.userId) and (AuditLogTbl.entityId eq code.value) }.map { row ->
                row[AuditLogTbl.action] to row[AuditLogTbl.changes]?.map { change -> change.jsonObject.let { Triple(it.text("field"), it.text("from"), it.text("to")) } }
            }
        }
        assertEquals(listOf("ORDER_MEDIA_SET" to listOf(Triple("media", null, """{"1":1,"order":1}""")), "ORDER_PHOTO_SENT" to null), rows)
        assertEquals(HttpStatusCode.Forbidden, setPhotos(code, "[${item(photo)}]", panel.callerWith(Role.CATALOG_MANAGER)).status)
    }
}
