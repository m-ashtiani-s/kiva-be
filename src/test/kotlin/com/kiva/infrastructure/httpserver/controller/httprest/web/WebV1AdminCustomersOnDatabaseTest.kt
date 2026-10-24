package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.auth0.jwt.JWT
import com.kiva.common.patch.Patch
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import com.kiva.domain.aggregate.user.model.ProfilePatch
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel's customers page over kiva_test, with staff and customers who really logged in: GET /admin/customers and
 * PUT /admin/customers/{customerId}/roles. Step 9 of the login plan is done when these pass (with BootstrapAdminTest).
 * Test phones start with 0990 and are deleted after each test.
 */
class WebV1AdminCustomersOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-01-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db
    private val users = UserRepositoryImpl()

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private class Login(val accessToken: String, val cookie: String)

    /** A user with [roles] (and [name] if given), made before any login, so their login is a panel session when staff. */
    private fun account(phone: String, vararg roles: Role, name: String? = null): Long = transaction(db) {
        var user = users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone), clock.now).user
        if (roles.isNotEmpty()) user = user.setPanelRoles(roles.toList(), by = UserId.SYSTEM).getOrNull() ?: error("refused")
        if (name != null) {
            val (first, last) = name.split(' ')
            user = user.updateProfile(ProfilePatch(firstName = Patch.Set(first), lastName = Patch.Set(last)), LocalDate.of(2036, 1, 1)).getOrNull() ?: error("refused")
        }
        if (roles.isNotEmpty() || name != null) users.save(user)
        user.id.value
    }

    private suspend fun ApplicationTestBuilder.login(phone: String): Login {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${stack.lastCodeSentTo(phone).value}"}""")
        }
        val token = Json.parseToJsonElement(response.bodyAsText()).jsonObject["accessToken"]?.jsonPrimitive?.content ?: error("no token")
        val cookie = response.headers[HttpHeaders.SetCookie].orEmpty().substringAfter("kiva_rt=").substringBefore(';')
        return Login(token, cookie)
    }

    private suspend fun ApplicationTestBuilder.setRoles(token: String, customerId: Any, body: String): HttpResponse =
        client.put("$API_V1/admin/customers/$customerId/roles") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.customers(token: String, q: String? = null, size: Int? = null): HttpResponse =
        client.get("$API_V1/admin/customers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (q != null) parameter("q", q)
            if (size != null) parameter("size", size)
        }

    private suspend fun ApplicationTestBuilder.refresh(cookie: String): HttpResponse =
        client.post("$API_V1/auth/refresh") {
            header(HttpHeaders.Origin, ORIGIN)
            header(HttpHeaders.Cookie, "kiva_rt=$cookie")
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.roles() = this["roles"]?.jsonArray?.map { it.jsonPrimitive.content }

    private suspend fun HttpResponse.phones(): List<String> = json()["items"]?.jsonArray?.map { it.jsonObject["phone"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()

    private fun sessionsOf(userId: Long) = transaction(db) {
        AuthSessionTbl.selectAll().where { AuthSessionTbl.userId eq userId }.orderBy(AuthSessionTbl.id).toList()
    }

    @Test
    fun `an admin gives a customer OPERATOR, their sessions end, their next login has it, and it is recorded`() = stack.app {
        val adminId = account(phone(1), Role.ADMIN)
        val admin = login(phone(1))
        val customerId = account(phone(2))
        val laptop = login(phone(2))
        clock.advance(Duration.ofSeconds(121))
        login(phone(2))

        val response = setRoles(admin.accessToken, customerId, """{"roles": ["OPERATOR"]}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val customer = response.json()
        assertEquals(listOf("CUSTOMER", "OPERATOR"), customer.roles())
        assertEquals(phone(2), customer["phone"]?.jsonPrimitive?.content)
        assertEquals("0", customer["ordersCount"]?.jsonPrimitive?.content)
        assertEquals("0", customer["totalSpent"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, customer["lastOrderAt"])

        assertEquals(listOf(RevokeReason.ROLES_CHANGED, RevokeReason.ROLES_CHANGED), sessionsOf(customerId).map { it[AuthSessionTbl.revokeReason] })
        assertEquals(HttpStatusCode.Unauthorized, refresh(laptop.cookie).status)

        clock.advance(Duration.ofSeconds(121))
        val nextLogin = login(phone(2))
        assertEquals(listOf("CUSTOMER", "OPERATOR"), JWT.decode(nextLogin.accessToken).getClaim("roles").asList(String::class.java))
        assertEquals(SessionKind.PANEL, sessionsOf(customerId).last()[AuthSessionTbl.kind])

        val event = transaction(db) {
            SecurityEventTbl.selectAll().where { (SecurityEventTbl.type eq "ROLES_CHANGED") and (SecurityEventTbl.userId eq customerId) }.single()
        }
        val detail = event[SecurityEventTbl.detail]
        assertEquals("$adminId", detail["by"]?.jsonPrimitive?.content)
        assertEquals("CUSTOMER", detail["from"]?.jsonPrimitive?.content)
        assertEquals("CUSTOMER,OPERATOR", detail["to"]?.jsonPrimitive?.content)
        assertEquals("2", detail["sessionsEnded"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an admin can't change their own roles, and an operator can't use this at all`() = stack.app {
        val adminId = account(phone(3), Role.ADMIN)
        val admin = login(phone(3))
        account(phone(4), Role.OPERATOR)
        val operator = login(phone(4))
        val customerId = account(phone(5))

        val own = setRoles(admin.accessToken, adminId, """{"roles": ["OPERATOR"]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, own.status)
        assertEquals("ROLE_CHANGE_NOT_ALLOWED", own.json()["code"]?.jsonPrimitive?.content)
        assertEquals(listOf("CUSTOMER", "ADMIN"), transaction(db) { UserTbl.selectAll().where { UserTbl.id eq adminId }.single()[UserTbl.roles] })

        assertEquals(HttpStatusCode.Forbidden, setRoles(operator.accessToken, customerId, """{"roles": ["ADMIN"]}""").status)
    }

    @Test
    fun `a list without CUSTOMER keeps it, an unknown role is 400 on its place, an unknown customer is 404`() = stack.app {
        account(phone(6), Role.ADMIN)
        val admin = login(phone(6))
        val customerId = account(phone(7))

        assertEquals(listOf("CUSTOMER", "SUPPORT"), setRoles(admin.accessToken, customerId, """{"roles": ["SUPPORT"]}""").json().roles())

        val unknown = setRoles(admin.accessToken, customerId, """{"roles": ["SUPPORT", "OWNER"]}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("roles.1", unknown.json()["errors"]?.jsonArray?.single()?.jsonObject?.get("field")?.jsonPrimitive?.content)

        assertEquals(HttpStatusCode.NotFound, setRoles(admin.accessToken, Long.MAX_VALUE, """{"roles": ["SUPPORT"]}""").status)
        assertEquals(HttpStatusCode.NotFound, setRoles(admin.accessToken, "abc", """{"roles": ["SUPPORT"]}""").status)
    }

    @Test
    fun `the same roles again change nothing and end no session`() = stack.app {
        account(phone(8), Role.ADMIN)
        val admin = login(phone(8))
        val operatorId = account(phone(9), Role.OPERATOR)
        val operator = login(phone(9))

        assertEquals(HttpStatusCode.OK, setRoles(admin.accessToken, operatorId, """{"roles": ["OPERATOR", "CUSTOMER"]}""").status)

        assertEquals(null, sessionsOf(operatorId).single()[AuthSessionTbl.revokedOn])
        assertEquals(HttpStatusCode.OK, refresh(operator.cookie).status)
    }

    @Test
    fun `admin and support see the customers, an operator doesn't`() = stack.app {
        account(phone(10), Role.ADMIN)
        account(phone(11), Role.SUPPORT)
        account(phone(12), Role.OPERATOR)

        assertEquals(HttpStatusCode.OK, customers(login(phone(10)).accessToken).status)
        assertEquals(HttpStatusCode.OK, customers(login(phone(11)).accessToken).status)
        assertEquals(HttpStatusCode.Forbidden, customers(login(phone(12)).accessToken).status)
    }

    @Test
    fun `the list is newest first, page by page, and finds a customer by phone or name`() = stack.app {
        account(phone(20), Role.SUPPORT)
        val support = login(phone(20)).accessToken
        clock.advance(Duration.ofMinutes(1))
        account(phone(21), name = "سارا محمدی")
        clock.advance(Duration.ofMinutes(1))
        account(phone(22), name = "علی رضایی")
        clock.advance(Duration.ofMinutes(1))
        account(phone(23))

        val firstPage = customers(support, q = "0990", size = 2)
        assertEquals(listOf(phone(23), phone(22)), firstPage.phones())
        val meta = firstPage.json()["meta"]?.jsonObject ?: error("no meta")
        assertEquals("4", meta["totalItems"]?.jsonPrimitive?.content)
        assertEquals("true", meta["hasNext"]?.jsonPrimitive?.content)

        assertEquals(listOf(phone(22)), customers(support, q = "علي").phones())
        assertEquals(listOf(phone(21)), customers(support, q = "سارا محمدی").phones())
        assertEquals(listOf(phone(21)), customers(support, q = "+98 990 000 0021").phones())
        assertEquals(listOf(phone(23)), customers(support, q = "۰۰۰۰۰۲۳").phones())
        assertTrue(customers(support, q = "nobody-like-this").phones().isEmpty())

        val sara = customers(support, q = "سارا").json()["items"]?.jsonArray?.single()?.jsonObject ?: error("not found")
        assertEquals("سارا محمدی", sara["fullName"]?.jsonPrimitive?.content)
        assertEquals(listOf("CUSTOMER"), sara.roles())
    }

    @Test
    fun `a deleted account is no customer - not in the list, not by its old phone, its page and its roles 404`() = stack.app {
        account(phone(30), Role.ADMIN)
        val admin = login(phone(30)).accessToken
        val gone = account(phone(31), name = "سارا محمدی")
        account(phone(32))
        transaction(db) { users.save(users.findById(UserId.constructFromPersisted(gone))!!.erase(clock.now)) }

        assertEquals(listOf(phone(32), phone(30)), customers(admin, q = "0990").phones())
        val all = customers(admin, size = 100)
        assertEquals(HttpStatusCode.OK, all.status, all.bodyAsText())
        val everyone = all.json()["items"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content }.orEmpty()
        assertTrue(gone.toString() !in everyone, "the whole list has the deleted account")
        assertTrue(customers(admin, q = phone(31)).phones().isEmpty())
        assertTrue(customers(admin, q = "سارا").phones().isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("$API_V1/admin/customers/$gone") { header(HttpHeaders.Authorization, "Bearer $admin") }.status)
        assertEquals(HttpStatusCode.NotFound, setRoles(admin, gone, """{"roles":["OPERATOR"]}""").status)
    }
}
