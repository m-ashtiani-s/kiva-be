package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.AuditChange
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.TRACE_ID_HEADER
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The panel's change log over kiva_test: a roles change writes its row (and a refused one none), and
 * GET /admin/audit-log reads them back, newest first, with its filters, its Persian sentence and the actor as they are.
 *
 * Rows can't be removed (younger than 90 days), so every assertion looks only at the rows of this test's own staff.
 */
class WebV1AdminAuditOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-03-06T09:00:00Z"))
    private val stack = PanelTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteWhatWasStored()

    private fun rowsBy(actorId: Long) = transaction(stack.db) {
        AuditLogTbl.selectAll().where { AuditLogTbl.actorId eq actorId }.toList()
    }

    private suspend fun ApplicationTestBuilder.setRoles(admin: PanelTestStack.Caller, customerId: Long, vararg roles: String): HttpResponse =
        client.put("$API_V1/admin/customers/$customerId/roles") {
            header(HttpHeaders.Authorization, admin.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"roles": [${roles.joinToString { "\"$it\"" }}]}""")
        }

    private suspend fun ApplicationTestBuilder.log(admin: PanelTestStack.Caller, vararg parameters: Pair<String, Any>): HttpResponse =
        client.get("$API_V1/admin/audit-log") {
            header(HttpHeaders.Authorization, admin.bearer)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }

    private fun HttpResponse.items(text: String): JsonArray = Json.parseToJsonElement(text).jsonObject.getValue("items").jsonArray

    private fun record(actor: Long, at: Instant, action: AuditAction = AuditAction.PRODUCT_UPDATED, entityId: String = "12", label: String? = "ماهک") =
        transaction(stack.db) {
            stack.auditTrail.record(
                AuditEntry(at, PanelActor(UserId.constructFromPersisted(actor), "5.160.12.34"), action, AuditEntityType.PRODUCT, entityId, label, null),
            )
        }

    @Test
    fun `a roles change writes one USER_ROLES_CHANGED row with the roles before and after, the ip and the traceId`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)
        val customer = stack.caller(PanelTestStack.phone(90), name = "سارا محمدی")

        val response = setRoles(admin, customer.userId, "OPERATOR")

        assertEquals(HttpStatusCode.OK, response.status)
        val row = rowsBy(admin.userId).single()
        assertEquals("USER_ROLES_CHANGED", row[AuditLogTbl.action])
        assertEquals("USER", row[AuditLogTbl.entityType])
        assertEquals(customer.userId.toString(), row[AuditLogTbl.entityId])
        assertEquals("سارا محمدی", row[AuditLogTbl.entityLabel])
        assertEquals(
            listOf(AuditChange("roles", """["CUSTOMER"]""", """["CUSTOMER","OPERATOR"]""")),
            row[AuditLogTbl.changes]?.let(AuditTrailImpl::changesOf),
        )
        assertEquals(clock.now, row[AuditLogTbl.at].toInstant())
        assertNotNull(row[AuditLogTbl.ip])
        assertEquals(response.headers[TRACE_ID_HEADER], row[AuditLogTbl.traceId])
    }

    @Test
    fun `a refused roles change and the same roles again write no row`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)
        val operator = stack.callerWith(Role.OPERATOR)

        assertEquals(HttpStatusCode.UnprocessableEntity, setRoles(admin, admin.userId, "SUPPORT").status)
        assertEquals(HttpStatusCode.OK, setRoles(admin, operator.userId, "OPERATOR").status)

        assertEquals(emptyList(), rowsBy(admin.userId))
    }

    @Test
    fun `the log is newest first, with the actor as they are now and a Persian sentence`() = stack.app {
        val admin = stack.caller(PanelTestStack.phone(91), Role.ADMIN, name = "لیلا احمدی")
        record(admin.userId, Instant.parse("2036-03-05T09:00:00Z"), AuditAction.PRODUCT_CREATED)
        transaction(stack.db) {
            stack.auditTrail.record(
                AuditEntry(
                    Instant.parse("2036-03-06T08:00:00Z"), PanelActor(admin.user.id, "5.160.12.34"), AuditAction.PRODUCT_PRICE_CHANGED,
                    AuditEntityType.PRODUCT, "12", "ماهک", listOf(AuditChange("price", "1890000", "1790000")),
                ),
            )
        }

        val response = log(admin, "actorId" to admin.userId)

        assertEquals(HttpStatusCode.OK, response.status)
        val items = response.items(response.bodyAsText())
        assertEquals(listOf("PRODUCT_PRICE_CHANGED", "PRODUCT_CREATED"), items.map { it.jsonObject.getValue("action").jsonPrimitive.content })
        val newest = items.first().jsonObject
        assertEquals("قیمت از ۱٬۸۹۰٬۰۰۰ به ۱٬۷۹۰٬۰۰۰ تومان تغییر کرد", newest.getValue("summary").jsonPrimitive.content)
        assertEquals("محصول «ماهک» ساخته شد", items.last().jsonObject.getValue("summary").jsonPrimitive.content)
        val actor = newest.getValue("actor").jsonObject
        assertEquals(admin.userId, actor.getValue("id").jsonPrimitive.content.toLong())
        assertEquals("لیلا احمدی", actor.getValue("fullName").jsonPrimitive.content)
        assertEquals(PanelTestStack.phone(91), actor.getValue("phone").jsonPrimitive.content)
        assertEquals("5.160.12.34", newest.getValue("ip").jsonPrimitive.content)
        val change = newest.getValue("changes").jsonArray.single().jsonObject
        assertEquals(listOf("price", "1890000", "1790000"), listOf("field", "from", "to").map { change.getValue(it).jsonPrimitive.content })
        assertEquals(JsonNull, items.last().jsonObject.getValue("changes"))
        // Every field of the contract is there, the nullable ones too.
        assertEquals(setOf("id", "at", "actor", "action", "entityType", "entityId", "entityLabel", "summary", "changes", "ip"), newest.keys)
    }

    @Test
    fun `one thing's history - entityType with entityId, or entityId alone`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)
        record(admin.userId, clock.now, entityId = "12")
        record(admin.userId, clock.now, entityId = "13")

        val typed = log(admin, "actorId" to admin.userId, "entityType" to "PRODUCT", "entityId" to "12")
        val alone = log(admin, "actorId" to admin.userId, "entityId" to "13")

        assertEquals(listOf("12"), typed.items(typed.bodyAsText()).map { it.jsonObject.getValue("entityId").jsonPrimitive.content })
        assertEquals(listOf("13"), alone.items(alone.bodyAsText()).map { it.jsonObject.getValue("entityId").jsonPrimitive.content })
    }

    @Test
    fun `from and to are Tehran days - 21 00 UTC on Thursday is Friday`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)
        // 2036-03-06 is a Thursday; 21:00 UTC is 00:30 of Friday in Tehran.
        record(admin.userId, Instant.parse("2036-03-06T21:00:00Z"))

        val friday = log(admin, "actorId" to admin.userId, "from" to "2036-03-07", "to" to "2036-03-07")
        val thursday = log(admin, "actorId" to admin.userId, "from" to "2036-03-06", "to" to "2036-03-06")

        assertEquals(1, friday.items(friday.bodyAsText()).size)
        assertEquals(0, thursday.items(thursday.bodyAsText()).size)
    }

    @Test
    fun `from after to is 400 on to - a bad entityType is 400 on its name`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)

        val range = log(admin, "from" to "2036-03-07", "to" to "2036-03-06")
        val type = log(admin, "entityType" to "BAG")

        assertEquals(HttpStatusCode.BadRequest, range.status)
        assertTrue("DATE_RANGE_INVALID" in range.bodyAsText() && "\"to\"" in range.bodyAsText(), range.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, type.status)
        assertTrue("AUDIT_ENTITY_TYPE_INVALID" in type.bodyAsText(), type.bodyAsText())
    }

    @Test
    fun `only an admin reads the log - support is 403, no token is 401`() = stack.app {
        val support = client.get("$API_V1/admin/audit-log") { header(HttpHeaders.Authorization, stack.callerWith(Role.SUPPORT).bearer) }
        val catalog = client.get("$API_V1/admin/audit-log") { header(HttpHeaders.Authorization, stack.callerWith(Role.CATALOG_MANAGER).bearer) }
        val nobody = client.get("$API_V1/admin/audit-log")

        assertEquals(HttpStatusCode.Forbidden, support.status)
        assertEquals(HttpStatusCode.Forbidden, catalog.status)
        assertEquals(HttpStatusCode.Unauthorized, nobody.status)
    }

    @Test
    fun `a row whose actor has no name has fullName null`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)
        record(admin.userId, clock.now, label = null)

        val response = log(admin, "actorId" to admin.userId)
        val item = response.items(response.bodyAsText()).single().jsonObject

        assertEquals(JsonNull, item.getValue("actor").jsonObject.getValue("fullName"))
        assertEquals(JsonNull, item.getValue("entityLabel"))
        assertEquals("محصول «12» ویرایش شد", item.getValue("summary").jsonPrimitive.content)
    }
}
