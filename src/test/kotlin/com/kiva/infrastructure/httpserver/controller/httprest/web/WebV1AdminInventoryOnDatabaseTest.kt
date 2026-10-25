package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.InventoryAdjustmentQmTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST` and `GET /admin/inventory/adjustments` over kiva_test (step 1.8), with the panel's real stack, and V25's
 * backfill checked against what the projector writes.
 */
class WebV1AdminInventoryOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-02T09:00:00Z"))
    private val stack = PanelTestStack(clock)
    private val catalog = StoredCatalog()
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() {
        catalog.deleteAll()
        stack.deleteWhatWasStored()
    }

    private val manager get() = stack.callerWith(Role.CATALOG_MANAGER)

    private suspend fun ApplicationTestBuilder.adjust(variantId: VariantId, delta: Int, reason: String, note: String? = null, caller: PanelTestStack.Caller = manager): HttpResponse =
        client.post("$API_V1/admin/inventory/adjustments") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":${variantId.value},"delta":$delta,"reason":"$reason"${note?.let { ""","note":"$it"""" } ?: ""}}""")
        }

    private suspend fun ApplicationTestBuilder.history(vararg parameters: Pair<String, String>): HttpResponse = client.get("$API_V1/admin/inventory/adjustments") {
        header(HttpHeaders.Authorization, manager.bearer)
        parameters.forEach { (name, value) -> parameter(name, value) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code(): String = json().getValue("code").jsonPrimitive.content
    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray?.map { "${it.jsonObject.getValue("field").jsonPrimitive.content} ${it.jsonObject.getValue("code").jsonPrimitive.content}" }.orEmpty()

    private fun color(key: String = "lilac"): VariantId = catalog.newProduct(listOf(NewColor(key))).variants.single().id

    private fun stock(variantId: VariantId, bags: Int) = catalog.stock(variantId) {
        it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, catalog.staff, clock.instant())
    }

    private fun reserve(variantId: VariantId, bags: Int, order: String = "KV-555001") = catalog.stock(variantId) {
        it.reserve(OrderCode.constructFromPersisted(order), Quantity.constructFromPersisted(bags), UserId.SYSTEM, clock.instant())
    }

    // ============ the history's rows ============

    /** One history row as the database has it, without its own id (which a backfill numbers anew). */
    private data class Row(val eventId: String, val variantId: Long, val productId: Long, val sku: String, val delta: Int, val reason: String, val note: String?, val stockAfter: Int, val by: Long)

    private fun rowsOf(variantIds: List<VariantId>): List<Row> = transaction(db) {
        InventoryAdjustmentQmTbl.selectAll().where { InventoryAdjustmentQmTbl.variantId inList variantIds.map { it.value } }
            .orderBy(InventoryAdjustmentQmTbl.variantId).orderBy(InventoryAdjustmentQmTbl.occurredOn).orderBy(InventoryAdjustmentQmTbl.id)
            .map {
                Row(
                    it[InventoryAdjustmentQmTbl.eventId].toString(), it[InventoryAdjustmentQmTbl.variantId], it[InventoryAdjustmentQmTbl.productId],
                    it[InventoryAdjustmentQmTbl.sku], it[InventoryAdjustmentQmTbl.delta], it[InventoryAdjustmentQmTbl.reason], it[InventoryAdjustmentQmTbl.note],
                    it[InventoryAdjustmentQmTbl.stockAfter], it[InventoryAdjustmentQmTbl.occurredByUserId],
                )
            }
    }

    /** V25's INSERT, as the file has it, made to skip the rows already there (the other tests' colors). */
    private fun backfillStatement(): String =
        File("src/main/resources/db/migration/V25__inventory_adjustments.sql").readText()
            .split(';')
            .map { statement -> statement.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").trim() }
            .single { it.startsWith("INSERT") } + "\nON CONFLICT (\"eventId\") DO NOTHING"

    @Test
    fun `V25's backfill gives exactly the rows the projector wrote - stock after counts shipments, not reservations`() {
        val lilac = color("lilac")
        val black = color("black")
        stock(lilac, 10)
        reserve(lilac, 3)
        catalog.stock(lilac) { it.ship(OrderCode.constructFromPersisted("KV-555001"), UserId.SYSTEM, clock.instant()) }
        catalog.stock(lilac) { it.adjust(StockDelta.constructFromPersisted(-2), AdjustmentReason.DAMAGED, AdjustmentNote.constructFromPersisted("خیس شد"), catalog.staff, clock.instant()) }
        stock(black, 4)
        val projected = rowsOf(listOf(lilac, black))

        val backfilled = transaction(db) {
            InventoryAdjustmentQmTbl.deleteWhere { InventoryAdjustmentQmTbl.variantId inList listOf(lilac.value, black.value) }
            TransactionManager.current().exec(backfillStatement())
            rowsOf(listOf(lilac, black)).also { rollback() }
        }

        assertEquals(projected, backfilled)
        assertEquals(listOf(10, 5, 4), projected.map { it.stockAfter })
        assertEquals(listOf("RESTOCK", "DAMAGED", "RESTOCK"), projected.map { it.reason })
    }

    // ============ adjusting ============

    @Test
    fun `RESTOCK of 5 is 201 with its id, the stock after and who made it by name - first in the history`() = stack.app {
        val lilac = color()
        stock(lilac, 2)
        val maker = stack.caller(PanelTestStack.phone(60), Role.CATALOG_MANAGER, name = "لیلا احمدی")

        val response = adjust(lilac, 5, "RESTOCK", note = "بارنامه ۲۳۴", caller = maker)

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(7, body.getValue("stockAfter").jsonPrimitive.int)
        assertEquals("لیلا احمدی", body.getValue("createdBy").jsonPrimitive.content)
        assertEquals("بارنامه ۲۳۴", body.getValue("note").jsonPrimitive.content)
        val first = history().json().getValue("items").jsonArray.first().jsonObject
        assertEquals(body.getValue("id"), first.getValue("id"))

        val row = transaction(db) {
            AuditLogTbl.selectAll().where { (AuditLogTbl.action eq "INVENTORY_ADJUSTED") and (AuditLogTbl.entityId eq lilac.value.toString()) }.single()
        }
        val changes = row[AuditLogTbl.changes]!!.map { it.jsonObject }.associate { it.getValue("field").jsonPrimitive.content to it }
        assertEquals("2" to "7", changes.getValue("stockOnHand").let { it.getValue("from").jsonPrimitive.content to it.getValue("to").jsonPrimitive.content })
        assertEquals("RESTOCK", changes.getValue("reason").getValue("to").jsonPrimitive.content)
    }

    @Test
    fun `DAMAGED with +2 is ADJUSTMENT_DIRECTION_INVALID, 0 is STOCK_DELTA_INVALID, more than is free is 422 QUANTITY_EXCEEDS_STOCK`() = stack.app {
        val lilac = color()
        stock(lilac, 5)
        reserve(lilac, 3)

        val wrongWay = adjust(lilac, 2, "DAMAGED")
        val nothing = adjust(lilac, 0, "STOCKTAKE")
        val tooMany = adjust(lilac, -3, "DAMAGED")

        assertEquals(listOf("delta ADJUSTMENT_DIRECTION_INVALID"), wrongWay.errors())
        assertEquals(listOf("delta STOCK_DELTA_INVALID"), nothing.errors())
        assertEquals(HttpStatusCode.UnprocessableEntity to "QUANTITY_EXCEEDS_STOCK", tooMany.status to tooMany.code())
        assertEquals(2, tooMany.json().getValue("meta").jsonObject.getValue("availableQuantity").jsonPrimitive.int)
        assertEquals(listOf(5), rowsOf(listOf(lilac)).map { it.stockAfter })
    }

    // ============ the history ============

    @Test
    fun `the history of one color only, newest first - an order's reservation is not in it`() = stack.app {
        val lilac = color("lilac")
        val black = color("black")
        stock(lilac, 6)
        clock.advance(Duration.ofMinutes(1))
        stock(black, 3)
        reserve(lilac, 2)
        clock.advance(Duration.ofMinutes(1))
        adjust(lilac, -1, "DAMAGED")

        val response = history("variantId" to lilac.value.toString())

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val items = response.json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("DAMAGED", "RESTOCK"), items.map { it.getValue("reason").jsonPrimitive.content })
        assertEquals(listOf(5, 6), items.map { it.getValue("stockAfter").jsonPrimitive.int })
        assertEquals(listOf(lilac.value), items.map { it.getValue("variantId").jsonPrimitive.content.toLong() }.distinct())
        assertEquals(2, response.json().getValue("meta").jsonObject.getValue("totalItems").jsonPrimitive.int)
        assertEquals("حساب حذف‌شده", items.last().getValue("createdBy").jsonPrimitive.content)
        assertEquals(JsonNull, items.last().getValue("note"))
        assertEquals(listOf("variantId VARIANT_ID_INVALID"), history("variantId" to "abc").errors())
    }

    @Test
    fun `20 changes at once on one color - the stock is their sum, with 20 rows of consecutive stock after`() = stack.app {
        val lilac = color()
        stock(lilac, 100)

        val answers = coroutineScope { (1..20).map { async { adjust(lilac, if (it % 2 == 0) 3 else -1, if (it % 2 == 0) "RESTOCK" else "DAMAGED") } }.awaitAll() }

        assertEquals(List(20) { HttpStatusCode.Created }, answers.map { it.status }, answers.map { it.bodyAsText() }.toString())
        val rows = rowsOf(listOf(lilac)).drop(1)
        assertEquals(20, rows.size)
        assertEquals(100 + 10 * 3 - 10, transaction(db) { InventoryRepositoryImpl().get(lilac)!!.onHand })
        // Each row's stock after is the one before it plus its own change: no row lost, none counted twice.
        rows.fold(100) { before, row ->
            assertEquals(before + row.delta, row.stockAfter, "$rows")
            row.stockAfter
        }
    }

    @Test
    fun `an OPERATOR is 403 on both`() = stack.app {
        val operator = stack.callerWith(Role.OPERATOR)
        val lilac = color()

        assertEquals(HttpStatusCode.Forbidden, adjust(lilac, 1, "RESTOCK", caller = operator).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("$API_V1/admin/inventory/adjustments") { header(HttpHeaders.Authorization, operator.bearer) }.status)
        assertTrue(rowsOf(listOf(lilac)).isEmpty())
    }
}
