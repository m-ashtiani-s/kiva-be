package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A customer's page, the role filter and «the last ADMIN» (step 4.3) on kiva_test. */
class WebV1AdminCustomerDetailOnDatabaseTest {

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

    private suspend fun ApplicationTestBuilder.detail(id: Any, caller: PanelTestStack.Caller): HttpResponse =
        client.get("$API_V1/admin/customers/$id") { header(HttpHeaders.Authorization, caller.bearer) }

    private suspend fun ApplicationTestBuilder.setRoles(id: Any, roles: String, caller: PanelTestStack.Caller): HttpResponse =
        client.put("$API_V1/admin/customers/$id/roles") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"roles":$roles}""")
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `a customer's page has the whole profile, the addresses default first and the last 5 orders in any status, newest first`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(701), name = "سارا محمدی")
        val support = panel.callerWith(Role.SUPPORT)
        val nila = panel.purchase.variantOf("nila-crossbody-bag", "black")
        val first = panel.paidOrders.savedAddress(sara.user.id)
        val second = panel.paidOrders.savedAddress(sara.user.id, PaidOrders.Destination.ISFAHAN)
        val codes = (1..5).map {
            clock.advance(Duration.ofMinutes(1))
            panel.paidOrders.paid(sara.user.id, nila, addressId = first)
        }
        clock.advance(Duration.ofMinutes(1))
        val (unpaid, _) = panel.paidOrders.place(sara.user.id, nila, addressId = second)

        val page = detail(sara.userId, support)

        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        val customer = page.json()
        assertEquals("سارا", customer.text("firstName"))
        assertEquals("محمدی", customer.text("lastName"))
        assertEquals("سارا محمدی", customer.text("fullName"))
        assertEquals("UNSPECIFIED", customer.text("gender"))
        assertEquals("5", customer.text("ordersCount"))
        assertEquals(listOf(first, second), customer.getValue("addresses").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() })
        val recent = customer.getValue("recentOrders").jsonArray.map { it.jsonObject }
        assertEquals(listOf(unpaid.value) + codes.reversed().take(4).map { it.value }, recent.map { it.text("code") })
        assertEquals("PENDING_PAYMENT", recent.first().text("status"))
        assertTrue(
            customer.keys.containsAll(
                setOf("id", "phone", "email", "totalSpent", "lastOrderAt", "defaultMessenger", "marketingSmsOptIn", "roles", "createdAt", "birthDate", "defaultMessengerPhone"),
            ),
        )
    }

    @Test
    fun `a customer nobody has, or an id that isn't one, is 404 - and only ADMIN and SUPPORT see the page`() = panel.app {
        val admin = panel.callerWith(Role.ADMIN)
        val sara = panel.caller(PanelTestStack.phone(702))

        assertEquals(HttpStatusCode.NotFound, detail(999_999_999, admin).status)
        assertEquals(HttpStatusCode.NotFound, detail("abc", admin).status)
        assertEquals(HttpStatusCode.OK, detail(sara.userId, admin).status)
        assertEquals(HttpStatusCode.Forbidden, detail(sara.userId, panel.callerWith(Role.OPERATOR)).status)
        assertEquals(HttpStatusCode.Forbidden, detail(sara.userId, panel.callerWith(Role.CATALOG_MANAGER)).status)
    }

    @Test
    fun `role=OPERATOR lists only operators, CUSTOMER everyone, and an unknown role is 400 on role`() = panel.app {
        val support = panel.callerWith(Role.SUPPORT)
        val operator = panel.callerWith(Role.OPERATOR)
        panel.caller(PanelTestStack.phone(703))

        val operators = client.get("$API_V1/admin/customers?role=OPERATOR&size=100") { header(HttpHeaders.Authorization, support.bearer) }.json()
        assertEquals(listOf(operator.user.activePhone.value), operators.getValue("items").jsonArray.map { it.jsonObject.text("phone") })
        val everyone = client.get("$API_V1/admin/customers?role=CUSTOMER&size=100") { header(HttpHeaders.Authorization, support.bearer) }.json()
        assertTrue(everyone.getValue("items").jsonArray.size >= 3)
        val unknown = client.get("$API_V1/admin/customers?role=WIZARD") { header(HttpHeaders.Authorization, support.bearer) }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(
            listOf("role ROLE_INVALID"),
            unknown.json().getValue("errors").jsonArray.map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" },
        )
    }

    @Test
    fun `two admins taking ADMIN from each other at once - one is done, the other is 403, and an ADMIN is always left, 10 times`() {
        // Both have passed the route's check of their roles before either changes anything: they meet at the roles lock.
        var bothIn = CountDownLatch(2)
        val users = object : UserRepository by UserRepositoryImpl() {
            override fun lockRoleChanges() {
                bothIn.countDown()
                bothIn.await(5, TimeUnit.SECONDS)
                UserRepositoryImpl().lockRoleChanges()
            }
        }
        val setRoles = SetUserRolesUc(panel.transactions, users, AuthSessionRepositoryImpl(), SecurityEventLogImpl(), panel.auditTrail, clock)
        val useCases = AdminCustomerUseCases(panel.checkPanelAccess, panel.findCustomers, panel.getCustomer, panel.getCustomerDetail, setRoles)

        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(trustedProxyHops = 0)
                configureAuthentication(panel.accessTokens, panel.accountCheck)
                routing { route(API_V1) { webV1AdminCustomers(useCases) } }
            }
            repeat(10) { round ->
                bothIn = CountDownLatch(2)
                val a = panel.caller(PanelTestStack.phone(710 + 2 * round), Role.ADMIN)
                val b = panel.caller(PanelTestStack.phone(711 + 2 * round), Role.ADMIN)

                val statuses = coroutineScope {
                    listOf(async { setRoles(b.userId, "[]", a).status }, async { setRoles(a.userId, "[]", b).status }).awaitAll()
                }

                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Forbidden), statuses.toSet(), "round $round: $statuses")
                val admins = transaction(db) { listOf(a, b).count { Role.ADMIN in UserRepositoryImpl().findById(it.user.id)!!.roles } }
                assertEquals(1, admins, "round $round")
            }
        }
    }
}
