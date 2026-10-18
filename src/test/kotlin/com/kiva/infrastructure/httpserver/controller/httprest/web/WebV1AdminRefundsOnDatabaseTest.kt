package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.refund.usecase.command.CreateRefundUc
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefundTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Manual refunds (`POST /admin/orders/{orderCode}/refunds`, `PATCH /admin/refunds/{refundId}`; ADMIN) on kiva_test. */
class WebV1AdminRefundsOnDatabaseTest {

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

    private val nilaOlive by lazy { panel.purchase.variantOf("nila-crossbody-bag", "olive") }
    private val nila by lazy { listOf("black", "olive", "red", "cream", "gray", "pink", "blue").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val admin by lazy { panel.callerWith(Role.ADMIN) }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(400 + n))

    private suspend fun ApplicationTestBuilder.open(code: OrderCode, body: String, caller: PanelTestStack.Caller = admin): HttpResponse =
        client.post("$API_V1/admin/orders/${code.value}/refunds") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.follow(id: Long, body: String, caller: PanelTestStack.Caller = admin): HttpResponse =
        client.patch("$API_V1/admin/refunds/$id") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.read(path: String, who: PanelTestStack.Caller = admin): JsonObject =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, who.bearer) }.json()

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.array(name: String): JsonArray = this[name]!!.jsonArray

    private suspend fun ApplicationTestBuilder.payableOf(code: OrderCode): Long = read("/admin/orders/${code.value}")["payable"]!!.jsonPrimitive.long

    @Test
    fun `a refund opens PENDING, expected in 72 hours - more than is refundable is 400 REFUND_EXCEEDS_PAID with what is`() = panel.app {
        val code = panel.paidOrders.paid(customer(1).user.id, nilaOlive)
        val paid = payableOf(code)

        val opened = open(code, """{"amount":500000,"reason":"کیف آسیب دیده بود","method":"BANK_TRANSFER"}""")

        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val refund = opened.json()
        assertEquals("PENDING", refund.text("status"))
        assertEquals("BANK_TRANSFER", refund.text("method"))
        assertEquals(clock.now.plus(Duration.ofHours(72)).toString(), refund.text("expectedBy"))
        assertEquals(JsonNull, refund["referenceId"])

        val tooMuch = open(code, """{"amount":${paid - 500_000 + 1},"reason":"دوباره"}""")
        assertEquals(HttpStatusCode.BadRequest, tooMuch.status)
        val problem = tooMuch.json()
        assertEquals("amount" to "REFUND_EXCEEDS_PAID", problem.array("errors").single().jsonObject.let { it.text("field") to it.text("code") })
        assertEquals(paid - 500_000, problem["meta"]!!.jsonObject["refundable"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.Created, open(code, """{"amount":${paid - 500_000},"reason":"بقیه‌اش"}""").status)
    }

    @Test
    fun `a missing reason, no amount, store credit or a return id are 400 on their fields - a code nobody has 404`() = panel.app {
        val code = panel.paidOrders.paid(customer(2).user.id, nilaOlive)

        val refused = open(code, """{"method":"STORE_CREDIT","returnId":12}""")

        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(
            setOf("amount" to "REFUND_AMOUNT_INVALID", "reason" to "REFUND_REASON_REQUIRED", "method" to "REFUND_METHOD_INVALID", "returnId" to "RETURN_NOT_FOUND"),
            refused.json().array("errors").map { it.jsonObject.let { e -> e.text("field") to e.text("code") } }.toSet(),
        )
        assertEquals(HttpStatusCode.NotFound, open(OrderCode.constructFromPersisted("KV-999999"), """{"amount":1,"reason":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, follow(999_999, """{"status":"PROCESSING"}""").status)
    }

    @Test
    fun `two refunds that fit alone but not together, opened at once 20 times - one is 201, the other 400, never more than paid`() = panel.app {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { round ->
                val code = panel.paidOrders.paid(customer(10 + round).user.id, nila[round % nila.size])
                val paid = payableOf(code)
                val half = paid / 2 + 1
                val start = CountDownLatch(1)
                val tries = (1..2).map {
                    pool.submit<Boolean> {
                        start.await()
                        runBlocking {
                            panel.adminOrders.createRefund.execute(CreateRefundUc.Cmd(code.value, half, "نیمی", null, null, PanelActor(admin.user.id, null)))
                        }.isSuccess
                    }
                }
                start.countDown()
                val outcomes = tries.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(listOf(false, true), outcomes.sorted(), "round $round")
                val promised = transaction(db) {
                    val orderId = RefundTbl.select(RefundTbl.orderId).where { RefundTbl.amount eq half }.map { it[RefundTbl.orderId] }.maxOrNull()!!
                    RefundTbl.select(RefundTbl.amount.sum()).where { (RefundTbl.orderId eq orderId) and (RefundTbl.status neq RefundStatus.FAILED) }
                        .single()[RefundTbl.amount.sum()]!!
                }
                assertTrue(promised <= paid, "round $round: $promised promised of $paid")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `following a refund - PROCESSING, then COMPLETED with the bank's reference - the order's refunded, timeline and SMS - again is 422`() = panel.app {
        val sara = customer(3)
        val code = panel.paidOrders.paid(sara.user.id, nilaOlive)
        val id = open(code, """{"amount":300000,"reason":"تخفیف جبرانی"}""").json()["id"]!!.jsonPrimitive.long

        val processing = follow(id, """{"status":"PROCESSING","note":"در حال انتقال"}""")
        assertEquals(HttpStatusCode.OK, processing.status, processing.bodyAsText())
        assertEquals("PROCESSING", processing.json().text("status"))

        val noReference = follow(id, """{"status":"COMPLETED"}""")
        assertEquals(HttpStatusCode.BadRequest, noReference.status)
        assertEquals("referenceId" to "REFERENCE_ID_REQUIRED", noReference.json().array("errors").single().jsonObject.let { it.text("field") to it.text("code") })

        clock.advance(Duration.ofMinutes(5))
        val completed = follow(id, """{"status":"COMPLETED","referenceId":"140507181234567","note":"به کارتت برگشت"}""")
        assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())
        assertEquals(clock.now.toString(), completed.json().text("completedAt"))

        val page = read("/me/orders/${code.value}", sara)
        assertEquals(300_000L, page["totals"]!!.jsonObject["refunded"]!!.jsonPrimitive.long)
        assertEquals("۳۰۰٬۰۰۰ تومان برگشت داده شد", page.array("timeline").last().jsonObject.text("label"))
        val shown = page.array("refunds").single().jsonObject
        assertEquals("COMPLETED" to "به کارتت برگشت", shown.text("status") to shown.text("statusNote"))
        assertEquals(listOf(sara.user.phone to code), panel.purchase.auth.sms.refundsCompleted.toList())

        val again = follow(id, """{"status":"COMPLETED","referenceId":"140507181234567"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
        assertEquals("INVALID_STATUS_TRANSITION", again.json().text("code"))
        assertEquals(HttpStatusCode.UnprocessableEntity, follow(id, """{"status":"FAILED"}""").status)
    }

    @Test
    fun `a failed refund gives its amount back to what is refundable, and the queue's refundStatus finds the open ones`() = panel.app {
        val code = panel.paidOrders.paid(customer(4).user.id, nilaOlive)
        val paid = payableOf(code)
        val id = open(code, """{"amount":$paid,"reason":"لغو تلفنی"}""").json()["id"]!!.jsonPrimitive.long

        assertTrue(code.value in read("/admin/orders?refundStatus=PENDING&size=100").array("items").map { it.jsonObject.text("code") })
        assertFalse(code.value in read("/admin/orders?refundStatus=PROCESSING&size=100").array("items").map { it.jsonObject.text("code") })
        assertEquals(HttpStatusCode.BadRequest, client.get("$API_V1/admin/orders?refundStatus=FAILED") { header(HttpHeaders.Authorization, admin.bearer) }.status)

        assertEquals(HttpStatusCode.BadRequest, open(code, """{"amount":1,"reason":"x"}""").status)
        assertEquals(HttpStatusCode.OK, follow(id, """{"status":"FAILED","note":"شماره شبا تأیید نشد؛ دوباره باهات تماس می‌گیریم"}""").status)
        assertEquals(HttpStatusCode.Created, open(code, """{"amount":$paid,"reason":"دوباره"}""").status)
    }

    @Test
    fun `an OPERATOR can do neither - the change log has both, the bank's reference by name only`() = panel.app {
        val code = panel.paidOrders.paid(customer(5).user.id, nilaOlive)
        val operator = panel.callerWith(Role.OPERATOR)
        assertEquals(HttpStatusCode.Forbidden, open(code, """{"amount":1,"reason":"x"}""", operator).status)

        val id = open(code, """{"amount":1000,"reason":"x"}""").json()["id"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.Forbidden, follow(id, """{"status":"PROCESSING"}""", operator).status)
        follow(id, """{"status":"COMPLETED","referenceId":"99887766"}""")

        val rows = transaction(db) {
            AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq admin.userId) and (AuditLogTbl.entityId eq id.toString()) }.map {
                it[AuditLogTbl.action] to it[AuditLogTbl.changes].toString()
            }
        }
        assertEquals(listOf("REFUND_CREATED", "REFUND_UPDATED"), rows.map { it.first })
        assertTrue(""""amount"""" in rows[0].second && "1000" in rows[0].second, rows[0].second)
        assertTrue(""""referenceId"""" in rows[1].second && "99887766" !in rows[1].second, rows[1].second)
    }
}
