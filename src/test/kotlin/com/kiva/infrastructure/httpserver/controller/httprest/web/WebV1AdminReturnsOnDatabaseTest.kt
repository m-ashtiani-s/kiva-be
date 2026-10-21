package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.seed.SeededCatalog
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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel's return queue, the decision and the pickup over kiva_test (roadmap step 7.4): returns asked for orders
 * delivered the way the shop does it, every answer held against the contract's AdminReturnRequest, with the SMS that
 * went and the change log's rows.
 */
class WebV1AdminReturnsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val returns = AskedReturns(panel, clock)
    private val sms get() = panel.purchase.auth.sms

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    /** A member of staff with [role], with a session started now. */
    private fun staff(role: Role) = panel.caller(PanelTestStack.phone(80 + Role.entries.indexOf(role)), role)

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private suspend fun ApplicationTestBuilder.decide(returnId: Any, body: String, who: PanelTestStack.Caller? = staff(Role.OPERATOR)): HttpResponse =
        client.post("$API_V1/admin/returns/$returnId/decision") {
            who?.let { header(HttpHeaders.Authorization, it.bearer) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.pickup(returnId: Any, body: String, who: PanelTestStack.Caller? = staff(Role.OPERATOR)): HttpResponse =
        client.put("$API_V1/admin/returns/$returnId/pickup") {
            who?.let { header(HttpHeaders.Authorization, it.bearer) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.queue(query: String, who: PanelTestStack.Caller? = staff(Role.OPERATOR)): HttpResponse =
        client.get("$API_V1/admin/returns$query") { who?.let { header(HttpHeaders.Authorization, it.bearer) } }

    private suspend fun ApplicationTestBuilder.adminOrderStatus(code: OrderCode): String? =
        client.get("$API_V1/admin/orders/${code.value}") { header(HttpHeaders.Authorization, staff(Role.OPERATOR).bearer) }.json().text("status")

    private suspend fun ApplicationTestBuilder.myOrder(code: OrderCode, who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, returns.fresh(who).bearer) }.json()

    /** The change log's rows of the return [returnId]: (action, its changes as "field: from -> to"). */
    private fun auditOf(returnId: Long): List<Pair<String, List<String>>> = transaction(db) {
        AuditLogTbl.selectAll().where { AuditLogTbl.entityId eq returnId.toString() }.orderBy(AuditLogTbl.id).map { row ->
            val changes = row[AuditLogTbl.changes]?.let { Json.parseToJsonElement(it.toString()).jsonArray }.orEmpty()
            row[AuditLogTbl.action] to changes.map { it.jsonObject.let { c -> "${c.text("field")}: ${c.text("from")} -> ${c.text("to")}" } }
        }
    }

    private fun codeOf(returnId: Long) = ReturnCode.forId(ReturnId.constructFromPersisted(returnId))

    private val adminReturnKeys = ContractSchemas.propertiesOf("ReturnRequest") + ContractSchemas.propertiesOf("AdminReturnRequest")

    private fun JsonObject.allowed(): List<String> = getValue("allowedTransitions").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `approved - APPROVED, the shop paying as its reason suggests, the default instructions, «مرجوعی تأیید شد» and one RETURN_DECIDED row`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara, "NOT_AS_PICTURED") }

        val approved = decide(returnId, """{"approve":true}""")

        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val ret = approved.json()
        assertEquals(adminReturnKeys, ret.keys)
        assertEquals(listOf("APPROVED", "KIVA", code.value), listOf(ret.text("status"), ret.text("shippingPaidBy"), ret.text("orderCode")))
        assertTrue(ret.text("instructions")!!.contains("هزینه‌ی ارسال با کیواست"), "the default text for the shop paying")
        assertEquals(listOf("PICKUP_SCHEDULED", "RECEIVED", "CLOSED"), ret.allowed())
        assertEquals(clock.now.toString(), ret.text("decidedAt"))
        listOf("receipt", "suggestedRefund", "rejectionReason", "pickup", "refund").forEach { assertEquals(JsonNull, ret[it], it) }
        val customer = ret.getValue("customer").jsonObject
        assertEquals(listOf(sara.userId.toString(), sara.user.phone.value), listOf(customer.text("id"), customer.text("phone")))
        assertEquals("RETURN_REQUESTED", adminOrderStatus(code), "approving doesn't move the order")
        assertEquals(listOf(sara.user.phone to codeOf(returnId)), sms.returnsApproved.toList())
        val rows = auditOf(returnId)
        assertEquals(listOf("RETURN_DECIDED"), rows.map { it.first })
        assertEquals(listOf("status: REQUESTED -> APPROVED"), rows.single().second, "the payer didn't change")
    }

    @Test
    fun `approved with the customer paying and staff's own words - both kept, the payer's change in the row`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara, "WRONG_ITEM") }

        val approved = decide(returnId, """{"approve":true,"shippingPaidBy":"CUSTOMER","instructions":"با پست پیشتاز بفرست","rejectionReason":"ignored"}""")

        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val ret = approved.json()
        assertEquals(listOf("CUSTOMER", "با پست پیشتاز بفرست"), listOf(ret.text("shippingPaidBy"), ret.text("instructions")))
        assertNull(ret.text("rejectionReason"), "a reason on an approval is ignored")
        assertEquals(listOf("status: REQUESTED -> APPROVED", "shippingPaidBy: KIVA -> CUSTOMER"), auditOf(returnId).single().second)
    }

    @Test
    fun `turned down without a reason is 400 - with one REJECTED, the order DELIVERED again, «مرجوع کردن» back on, «مرجوعی رد شد»`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }

        val noReason = decide(returnId, """{"approve":false}""")
        val tooShort = decide(returnId, """{"approve":false,"rejectionReason":"نه"}""")
        val rejected = decide(returnId, """{"approve":false,"rejectionReason":"کیف استفاده شده بود","shippingPaidBy":"BOTH"}""")

        assertEquals(HttpStatusCode.BadRequest, noReason.status)
        assertEquals(listOf("rejectionReason REJECTION_REASON_REQUIRED"), noReason.errors())
        assertEquals(listOf("rejectionReason REJECTION_REASON_REQUIRED"), tooShort.errors())
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        val ret = rejected.json()
        assertEquals(listOf("REJECTED", "کیف استفاده شده بود"), listOf(ret.text("status"), ret.text("rejectionReason")))
        assertNull(ret.text("instructions"), "no instructions for a return turned down")
        assertEquals(emptyList(), ret.allowed())
        val page = myOrder(code, sara)
        assertEquals("DELIVERED", page.text("status"))
        assertEquals("true", page.getValue("actions").jsonObject.text("canReturn"), "within its window it may be asked again")
        assertEquals(listOf(sara.user.phone to codeOf(returnId)), sms.returnsRejected.toList())
        assertEquals(listOf("status: REQUESTED -> REJECTED"), auditOf(returnId).single().second)
        with(returns) { asked(code, sara) }
    }

    @Test
    fun `a decision again is 422 INVALID_STATUS_TRANSITION - no second SMS, no second row`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }
        decide(returnId, """{"approve":true}""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        val again = decide(returnId, """{"approve":true}""")
        val rejectNow = decide(returnId, """{"approve":false,"rejectionReason":"دیر شد"}""")

        listOf(again, rejectNow).forEach { answer ->
            assertEquals(HttpStatusCode.UnprocessableEntity, answer.status)
            val body = answer.json()
            assertEquals(listOf("INVALID_STATUS_TRANSITION", "APPROVED"), listOf(body.text("code"), body.getValue("meta").jsonObject.text("from")))
        }
        assertEquals(1, sms.returnsApproved.size)
        assertEquals(1, auditOf(returnId).size)
    }

    @Test
    fun `the shape, every problem at once - and a return that can't be one or nobody's is 404`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }

        val nothing = decide(returnId, "{}")
        val wrong = decide(returnId, """{"approve":true,"shippingPaidBy":"BOTH","instructions":"${"ا".repeat(1001)}"}""")

        assertEquals(listOf("approve FIELD_REQUIRED"), nothing.errors())
        assertEquals(listOf("shippingPaidBy SHIPPING_PAYER_INVALID", "instructions TEXT_TOO_LONG"), wrong.errors())
        listOf(decide("abc", """{"approve":true}"""), decide(999_999_999, """{"approve":true}"""), pickup(0, """{"carrier":"POST"}""")).forEach {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("RETURN_NOT_FOUND", it.json().text("code"))
        }
        assertTrue(auditOf(returnId).isEmpty())
    }

    @Test
    fun `a pickup of a REQUESTED return is 422 - of an APPROVED one PICKUP_SCHEDULED and «جمع‌آوری مرجوعی» - again it replaces every field`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara, "MANUFACTURING_DEFECT") }
        val tomorrow = clock.now.plus(Duration.ofDays(1))

        val tooEarly = pickup(returnId, """{"carrier":"TIPAX"}""")
        decide(returnId, """{"approve":true}""")
        val scheduled = pickup(returnId, """{"carrier":"TIPAX","scheduledFor":"$tomorrow","trackingCode":"tx-۱۲۳۴۵۶","note":"ساعت ۱۰ تا ۱۲"}""")
        val again = pickup(returnId, """{"carrier":"POST","notifyCustomer":false}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, tooEarly.status)
        assertEquals("INVALID_STATUS_TRANSITION", tooEarly.json().text("code"))
        assertEquals(HttpStatusCode.OK, scheduled.status, scheduled.bodyAsText())
        val first = scheduled.json()
        assertEquals("PICKUP_SCHEDULED", first.text("status"))
        val firstPickup = first.getValue("pickup").jsonObject
        assertEquals(listOf("TIPAX", tomorrow.toString(), "TX-123456", "ساعت ۱۰ تا ۱۲"), listOf("carrier", "scheduledFor", "trackingCode", "note").map { firstPickup.text(it) })
        assertEquals(listOf("PICKUP_SCHEDULED", "RECEIVED", "CLOSED"), first.allowed())
        assertEquals(listOf(Triple(sara.user.phone, codeOf(returnId), firstPickup.text("carrierName")!!)), sms.returnPickups.toList())
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
        val secondPickup = again.json().getValue("pickup").jsonObject
        assertEquals(listOf("POST", null, null, null), listOf("carrier", "scheduledFor", "trackingCode", "note").map { secondPickup.text(it) })
        assertEquals(1, sms.returnPickups.size, "notifyCustomer false")
        assertEquals(listOf("RETURN_DECIDED", "RETURN_PICKUP_SCHEDULED", "RETURN_PICKUP_SCHEDULED"), auditOf(returnId).map { it.first })
        assertEquals(
            listOf("status: APPROVED -> PICKUP_SCHEDULED", "carrier: null -> TIPAX", "scheduledFor: null -> $tomorrow", "trackingCode: null -> TX-123456"),
            auditOf(returnId)[1].second,
        )
        assertEquals(listOf("carrier: TIPAX -> POST", "scheduledFor: $tomorrow -> null", "trackingCode: TX-123456 -> null"), auditOf(returnId)[2].second)
        assertEquals("RETURN_REQUESTED", adminOrderStatus(code), "a pickup doesn't move the order")
    }

    @Test
    fun `the same pickup asked again changes nothing - no row, no SMS`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }
        decide(returnId, """{"approve":true}""")
        pickup(returnId, """{"carrier":"POST","trackingCode":"PT-555555"}""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        val same = pickup(returnId, """{"carrier":"POST","trackingCode":"pt-555555"}""")

        assertEquals(HttpStatusCode.OK, same.status, same.bodyAsText())
        assertEquals(1, sms.returnPickups.size)
        assertEquals(listOf("RETURN_DECIDED", "RETURN_PICKUP_SCHEDULED"), auditOf(returnId).map { it.first })
    }

    @Test
    fun `a courier, a time 40 days on or 2 hours ago, a code that isn't one - 400 each on its field, all at once`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }
        decide(returnId, """{"approve":true}""")

        val wrong = pickup(returnId, """{"carrier":"COURIER","scheduledFor":"${clock.now.plus(Duration.ofDays(40))}","trackingCode":"a!","note":"${"ب".repeat(501)}"}""")
        val past = pickup(returnId, """{"carrier":"POST","scheduledFor":"${clock.now.minus(Duration.ofHours(2))}"}""")
        val unknown = pickup(returnId, """{"carrier":"BIKE","scheduledFor":"tomorrow"}""")
        val nothing = pickup(returnId, "{}")
        val justDone = pickup(returnId, """{"carrier":"POST","scheduledFor":"${clock.now.minus(Duration.ofMinutes(30))}"}""")

        assertEquals(
            listOf("carrier SHIPPING_METHOD_NOT_SUPPORTED", "scheduledFor PICKUP_TIME_INVALID", "trackingCode TRACKING_CODE_INVALID", "note TEXT_TOO_LONG"),
            wrong.errors(),
        )
        assertEquals(listOf("scheduledFor PICKUP_TIME_INVALID"), past.errors())
        assertEquals(listOf("carrier CARRIER_INVALID", "scheduledFor DATE_TIME_INVALID"), unknown.errors())
        assertEquals(listOf("carrier FIELD_REQUIRED"), nothing.errors())
        assertEquals(HttpStatusCode.OK, justDone.status, "written down a little late is fine")
    }

    @Test
    fun `a customer and a catalog manager are 403 on the queue, the decision and the pickup - no login is 401 - an admin may`() = panel.app {
        val sara = customer(1)
        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }

        listOf(returns.fresh(sara), staff(Role.CATALOG_MANAGER)).forEach { who ->
            assertEquals(HttpStatusCode.Forbidden, queue("", who).status)
            assertEquals(HttpStatusCode.Forbidden, decide(returnId, """{"approve":true}""", who).status)
            assertEquals(HttpStatusCode.Forbidden, pickup(returnId, """{"carrier":"POST"}""", who).status)
        }
        assertEquals(HttpStatusCode.Unauthorized, queue("", null).status)
        assertEquals(HttpStatusCode.Unauthorized, decide(returnId, """{"approve":true}""", null).status)
        assertEquals(HttpStatusCode.Unauthorized, pickup(returnId, """{"carrier":"POST"}""", null).status)
        assertTrue(auditOf(returnId).isEmpty())
        assertEquals(HttpStatusCode.OK, decide(returnId, """{"approve":true}""", staff(Role.ADMIN)).status)
    }

    @Test
    fun `the queue - one status, oldest first - newest first by default - FOO is 400 RETURN_STATUS_INVALID`() = panel.app {
        val asked = (1..3).map { n ->
            val who = customer(n)
            val code = with(returns) { delivered(who, nila[n % 2]) }
            clock.advance(Duration.ofMinutes(1))
            with(returns) { asked(code, who) }
        }
        decide(asked[0], """{"approve":true}""")
        decide(asked[2], """{"approve":true}""")

        val approvedOldest = queue("?status=APPROVED&sort=oldest&size=100")
        val all = queue("?size=100")
        val foo = queue("?status=FOO")
        val badSort = queue("?sort=price")

        assertEquals(HttpStatusCode.OK, approvedOldest.status, approvedOldest.bodyAsText())
        val approvedIds = approvedOldest.json().getValue("items").jsonArray.map { it.jsonObject.text("id")!!.toLong() }
        assertEquals(listOf(asked[0], asked[2]), approvedIds.filter { it in asked })
        val allBody = all.json()
        val allIds = allBody.getValue("items").jsonArray.map { it.jsonObject.text("id")!!.toLong() }
        assertEquals(asked.reversed(), allIds.filter { it in asked })
        allBody.getValue("items").jsonArray.forEach { assertEquals(adminReturnKeys, it.jsonObject.keys) }
        assertNotNull(allBody.getValue("meta").jsonObject.text("totalItems"))
        assertEquals(HttpStatusCode.BadRequest, foo.status)
        assertEquals(listOf("status RETURN_STATUS_INVALID"), foo.errors())
        assertEquals(HttpStatusCode.BadRequest, badSort.status)
        assertTrue((all.json().getValue("items") as JsonArray).isNotEmpty())
    }
}
