package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The panel's internal notes on an order (`POST /admin/orders/{orderCode}/notes`; ADMIN, OPERATOR) on kiva_test. */
class WebV1AdminOrderNotesOnDatabaseTest {

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

    private val operator by lazy { panel.caller(PanelTestStack.phone(201), Role.OPERATOR, name = "مریم رضایی") }

    private fun anOrder(): Pair<PanelTestStack.Caller, OrderCode> {
        val customer = panel.caller(PanelTestStack.phone(202))
        return customer to panel.paidOrders.paid(customer.user.id, nilaOlive)
    }

    private suspend fun ApplicationTestBuilder.note(code: String, text: String?, caller: PanelTestStack.Caller = operator): HttpResponse =
        client.post("$API_V1/admin/orders/$code/notes") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text) }.toString())
        }

    private suspend fun ApplicationTestBuilder.read(path: String, caller: PanelTestStack.Caller): JsonObject =
        Json.parseToJsonElement(client.get("$API_V1$path") { header(HttpHeaders.Authorization, caller.bearer) }.bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `a note is 201 with its author by name, and the order's page lists the notes oldest first`() = panel.app {
        val (_, code) = anOrder()
        val writtenAt = clock.now

        val first = note(code.value, "  مشتری زنگ زد، رنگ مشکی می‌خواد  ")
        clock.advance(Duration.ofMinutes(5))
        note(code.value, "گفتم فردا عکس می‌فرستیم\nخط دوم")

        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val created = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals("مشتری زنگ زد، رنگ مشکی می‌خواد", created.text("text"))
        assertEquals("مریم رضایی", created.text("author"))
        assertEquals(writtenAt.toString(), created.text("createdAt"))
        val notes = read("/admin/orders/${code.value}", operator)["notes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("مشتری زنگ زد، رنگ مشکی می‌خواد", "گفتم فردا عکس می‌فرستیم\nخط دوم"), notes.map { it.text("text") })
        assertEquals(created.text("id"), notes.first().text("id"))
    }

    @Test
    fun `an empty text or one of 1001 characters is 400 on text - nothing is written`() = panel.app {
        val (_, code) = anOrder()

        listOf(null to "NOTE_REQUIRED", "   " to "NOTE_REQUIRED", "ا".repeat(1001) to "NOTE_TOO_LONG").forEach { (text, error) ->
            val response = note(code.value, text)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val problem = Json.parseToJsonElement(response.bodyAsText()).jsonObject["errors"]!!.jsonArray.single().jsonObject
            assertEquals("text" to error, problem.text("field") to problem.text("code"))
        }
        assertEquals(HttpStatusCode.Created, note(code.value, "ا".repeat(1000)).status)
        assertEquals(1, read("/admin/orders/${code.value}", operator)["notes"]!!.jsonArray.size)
    }

    @Test
    fun `the customer's own page has no note, and the change log has the note's writing but never its text`() = panel.app {
        val (customer, code) = anOrder()
        note(code.value, "شماره‌ی کارت مشتری رو نپرسید")

        val page = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, customer.bearer) }.bodyAsText()
        assertFalse("کارت" in page, page)
        assertFalse("\"notes\"" in page)

        val rows = transaction(db) {
            AuditLogTbl.selectAll().where { (AuditLogTbl.actorId eq operator.userId) and (AuditLogTbl.action eq "ORDER_NOTE_ADDED") }.toList()
        }
        val row = rows.single()
        assertEquals("ORDER" to code.value, row[AuditLogTbl.entityType] to row[AuditLogTbl.entityId])
        assertNull(row[AuditLogTbl.changes])
        assertFalse(rows.toString().contains("کارت"))
    }

    @Test
    fun `the database never changes nor removes a note`() = panel.app {
        val (_, code) = anOrder()
        note(code.value, "یادداشت")

        assertFailsWith<ExposedSQLException> { transaction(db) { exec("UPDATE ordernote_tbl SET text = 'دیگه'") } }
        assertFailsWith<ExposedSQLException> { transaction(db) { exec("DELETE FROM ordernote_tbl") } }
        assertTrue(read("/admin/orders/${code.value}", operator)["notes"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `a code nobody has is 404 - a CATALOG_MANAGER is 403`() = panel.app {
        val (_, code) = anOrder()

        assertEquals(HttpStatusCode.NotFound, note("KV-999999", "یادداشت").status)
        assertEquals(HttpStatusCode.Forbidden, note(code.value, "یادداشت", panel.callerWith(Role.CATALOG_MANAGER)).status)
        assertEquals(HttpStatusCode.Created, note(code.value, "یادداشت", panel.callerWith(Role.ADMIN)).status)
    }
}
