package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentAttemptUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.order.AdminOrderFilterQuery
import com.kiva.application.usecase.order.usecase.query.FindAdminOrdersUc
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel's order queue and one order (`GET /admin/orders`, `GET /admin/orders/{orderCode}`; ADMIN, OPERATOR) over the
 * real stack on kiva_test, with orders placed and paid the way the shop does it. Monday 2026-10-12, 11:30 in Tehran.
 */
class WebV1AdminOrdersOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val orders = panel.paidOrders

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
    private val mahakBlack by lazy { panel.purchase.variantOf("mahak-shoulder-bag", "black") }

    private fun customer(n: Int, name: String? = null) = panel.caller(PanelTestStack.phone(100 + n), name = name)

    private suspend fun ApplicationTestBuilder.call(path: String, caller: PanelTestStack.Caller = panel.callerWith(Role.OPERATOR)): HttpResponse =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.array(name: String): JsonArray = this[name]!!.jsonArray

    private suspend fun ApplicationTestBuilder.codes(query: String): List<String> {
        val response = call("/admin/orders?size=100&$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json().array("items").map { it.jsonObject.text("code")!! }
    }

    private fun List<OrderCode>.values() = map { it.value }

    @Test
    fun `without a status the queue is what was paid - an unpaid order comes only when asked for, and newest is first`() = panel.app {
        val sara = customer(1)
        val unpaid = orders.place(sara.user.id, nila[0]).first
        clock.advance(Duration.ofMinutes(1))
        val first = orders.paid(sara.user.id, nila[1])
        clock.advance(Duration.ofMinutes(1))
        val second = orders.paid(sara.user.id, nila[2])

        val queue = codes("q=${sara.user.activePhone.value}")
        assertEquals(listOf(second, first).values(), queue)
        assertEquals(listOf(unpaid.value), codes("q=${sara.user.activePhone.value}&status=PENDING_PAYMENT"))
        assertEquals(listOf(first, second).values(), codes("q=${sara.user.activePhone.value}&sort=oldest"))
        assertEquals(setOf(unpaid.value, first.value, second.value), codes("q=${sara.user.activePhone.value}&status=PENDING_PAYMENT,PROCESSING").toSet())
    }

    @Test
    fun `statusCounts has all 13 statuses and stays the same whichever status tab is picked`() = panel.app {
        val sara = customer(2)
        orders.place(sara.user.id, nila[0])
        orders.paid(sara.user.id, nila[1])
        orders.paid(sara.user.id, nila[2])
        val phone = sara.user.activePhone.value

        val all = call("/admin/orders?q=$phone").json()["statusCounts"]!!.jsonObject
        val oneTab = call("/admin/orders?q=$phone&status=PROCESSING").json()["statusCounts"]!!.jsonObject

        assertEquals(OrderStatus.entries.map { it.name }.toSet(), all.keys)
        assertEquals(all, oneTab)
        assertEquals(2L, all["PROCESSING"]!!.jsonPrimitive.long)
        assertEquals(1L, all["PENDING_PAYMENT"]!!.jsonPrimitive.long)
        assertEquals(0L, all["SHIPPED"]!!.jsonPrimitive.long)
    }

    @Test
    fun `q finds an order by its code, its digits alone, the account's phone, the recipient's phone, and by names`() = panel.app {
        val maryam = customer(3, name = "مریم رضایی")
        val code = orders.paid(maryam.user.id, nila[0], to = PaidOrders.Destination.ISFAHAN)
        val other = orders.paid(customer(4).user.id, nila[1])

        assertEquals(listOf(code.value), codes("q=${code.value}"))
        assertEquals(listOf(code.value), codes("q=${code.value.removePrefix("KV-")}"))
        assertEquals(listOf(code.value), codes("q=${code.value.lowercase()}"))
        assertEquals(listOf(code.value), codes("q=${maryam.user.activePhone.value}"))
        assertEquals(listOf(code.value), codes("q=${PaidOrders.Destination.ISFAHAN.recipientPhone}"))
        assertEquals(listOf(code.value), codes("q=مریم رضایی"))
        assertEquals(listOf(code.value), codes("q=رضا کریمی"))
        assertTrue(code.value in codes("q=اصفهان"))
        assertTrue(other.value !in codes("q=اصفهان"))
    }

    @Test
    fun `filters - shipping method, reserved, awaiting photos`() = panel.app {
        val sara = customer(5)
        val home = orders.savedAddress(sara.user.id)
        val byPost = orders.paid(sara.user.id, nila[0])
        val byTipax = orders.paid(sara.user.id, nila[1], shippingMethod = "TIPAX")
        val held = orders.paid(sara.user.id, mahakBlack, reserve = true, addressId = home)
        val phone = sara.user.activePhone.value

        assertEquals(listOf(byTipax.value), codes("q=$phone&shippingMethod=TIPAX"))
        assertEquals(listOf(held.value), codes("q=$phone&reserved=true"))
        assertEquals(setOf(byPost.value, byTipax.value), codes("q=$phone&reserved=false").toSet())
        assertEquals(setOf(byPost.value, byTipax.value), codes("q=$phone&awaitingPhotos=true").toSet())
        assertEquals(listOf(held.value), codes("q=$phone&awaitingPhotos=false"))

        val row = call("/admin/orders?q=${held.value}").json().array("items").single().jsonObject
        assertEquals("RESERVED", row.text("status"))
        assertEquals(true, row["reserved"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(row.text("shipmentGroupCode")!!.startsWith("SG-"))
        assertEquals(clock.now.plus(Duration.ofDays(4)).epochSecond / 60, Instant.parse(row.text("reservationExpiresAt")).epochSecond / 60)
    }

    @Test
    fun `from and to are Tehran days - an order at 22h00 UTC on Thursday was placed on Friday`() = panel.app {
        clock.advance(Duration.between(clock.now, Instant.parse("2026-10-15T22:00:00Z")))
        val sara = customer(6)
        val code = orders.paid(sara.user.id, nila[0])
        val phone = sara.user.activePhone.value

        assertEquals(listOf(code.value), codes("q=$phone&from=2026-10-16&to=2026-10-16"))
        assertEquals(emptyList(), codes("q=$phone&from=2026-10-15&to=2026-10-15"))
        assertEquals(listOf(code.value), codes("q=$phone&from=۲۰۲۶-۱۰-۱۶"))
    }

    @Test
    fun `a wrong parameter is 400 on its own name, every mistake at once`() = panel.app {
        val response = call("/admin/orders?status=PROCESSING,LOST&reserved=maybe&from=yesterday&shippingMethod=CAMEL&q=${"a".repeat(101)}")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val errors = response.json().array("errors").map { it.jsonObject.text("field") to it.jsonObject.text("code") }.toSet()
        assertEquals(
            setOf(
                "status" to "ORDER_STATUS_INVALID", "reserved" to "BOOLEAN_INVALID", "from" to "DATE_INVALID", "shippingMethod" to "SHIPPING_METHOD_INVALID",
                "q" to "SEARCH_QUERY_TOO_LONG",
            ),
            errors,
        )
        assertEquals("DATE_RANGE_INVALID", call("/admin/orders?from=2026-10-16&to=2026-10-15").json().array("errors").single().jsonObject.text("code"))
        assertEquals("SORT_NOT_ALLOWED", call("/admin/orders?sort=cheapest").json().array("errors").single().jsonObject.text("code"))
    }

    @Test
    fun `one order - its page, the customer, every attempt to pay oldest first, and where it may go`() = panel.app {
        val maryam = customer(7, name = "مریم رضایی")
        val (code, firstAttempt) = orders.place(maryam.user.id, nila[0])
        orders.back(firstAttempt, paid = false)
        val key = UUID.randomUUID().toString()
        val retry = runBlocking {
            panel.purchase.startPaymentAttempt.execute(
                StartPaymentAttemptUc.Cmd(maryam.user.id, StartPaymentAttemptUc.Target.Order(code.value), key, RequestHash.of(key), null),
            )
        }.getOrNull()!!
        runBlocking { panel.purchase.startPayment.execute(StartPaymentUc.Cmd(maryam.user.id, retry)) }
        orders.back(retry.value, paid = true)

        val response = call("/admin/orders/${code.value}")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val order = response.json()
        assertEquals("PROCESSING", order.text("status"))
        assertEquals(listOf("CANCELLED", "SUCCEEDED"), order.array("payments").map { it.jsonObject.text("status") })
        assertEquals("SUCCEEDED", order["payment"]!!.jsonObject.text("status"))
        assertEquals(listOf("CANCELLED"), order.array("allowedTransitions").map { it.jsonPrimitive.content })
        val customer = order["customer"]!!.jsonObject
        assertEquals("مریم رضایی", customer.text("fullName"))
        assertEquals(maryam.user.activePhone.value, customer.text("phone"))
        assertEquals(1, customer["ordersCount"]!!.jsonPrimitive.int)
        assertNull(order.text("shipmentGroup"))
        assertEquals(emptyList(), order.array("draftItems"))
        assertTrue("address" in order && "timeline" in order && "items" in order && "refunds" in order && "notes" in order)
    }

    @Test
    fun `an unpaid order may go nowhere - a held one shows its group with its orders`() = panel.app {
        val sara = customer(8)
        val home = orders.savedAddress(sara.user.id)
        val unpaid = orders.place(sara.user.id, nila[0]).first
        val held = orders.paid(sara.user.id, mahakBlack, reserve = true, addressId = home)
        clock.advance(Duration.ofHours(1))
        val joined = orders.paid(sara.user.id, nila[1], addressId = home)

        assertEquals(emptyList(), call("/admin/orders/${unpaid.value}").json().array("allowedTransitions"))
        val group = call("/admin/orders/${joined.value}").json()["shipmentGroup"]!!.jsonObject
        assertEquals(listOf(held.value, joined.value), group.array("orderCodes").map { it.jsonPrimitive.content })
        assertTrue(group.text("code")!!.startsWith("SG-"))
    }

    @Test
    fun `a code nobody has, or one that can't be a code, is 404 ORDER_NOT_FOUND`() = panel.app {
        listOf("KV-999999", "nothing").forEach { code ->
            val response = call("/admin/orders/$code")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("ORDER_NOT_FOUND", response.json().text("code"))
        }
    }

    @Test
    fun `an OPERATOR and an ADMIN see the queue - a CATALOG_MANAGER is 403, nobody logged in 401`() = panel.app {
        assertEquals(HttpStatusCode.OK, call("/admin/orders").status)
        assertEquals(HttpStatusCode.OK, call("/admin/orders", panel.callerWith(Role.ADMIN)).status)
        assertEquals(HttpStatusCode.Forbidden, call("/admin/orders", panel.callerWith(Role.CATALOG_MANAGER)).status)
        assertEquals(HttpStatusCode.Forbidden, call("/admin/orders/KV-999999", panel.callerWith(Role.CATALOG_MANAGER)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/admin/orders").status)
    }

    @Test
    fun `a page of 20 takes as many reads as a page of 2 - its accounts are read at once, never one by one`() {
        val customers = (0 until 20).map { customer(20 + it) }
        customers.take(2).forEachIndexed { i, c -> orders.paid(c.user.id, nila[i % nila.size]) }
        val few = statementsOf { list() }
        customers.drop(2).forEachIndexed { i, c -> orders.paid(c.user.id, nila[i % nila.size]) }
        val many = statementsOf { list() }

        assertEquals(few, many)
        assertTrue(many <= 4, "$many statements: the count, the page with its groups, the status counts and the accounts")
    }

    private fun list() = runBlocking {
        val pageable = Pageable.makeNew(page = 1, size = 20, sort = "newest", allowedSorts = setOf("newest", "oldest")).getOrNull()!!
        panel.adminOrders.findOrders.execute(FindAdminOrdersUc.Qry(pageable, AdminOrderFilterQuery()))
    }.getOrNull()!!

    /** How many SQL statements [block] sends, read from the SQL log (SqlWithoutValuesLogger, at DEBUG). */
    private fun statementsOf(block: () -> Unit): Int {
        val logger = LoggerFactory.getLogger("Exposed") as Logger
        val before = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = before
        }
        return appender.list.count { it.formattedMessage.trimStart().startsWith("SELECT") }
    }
}
