package com.kiva.infrastructure.httpserver.auth

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.model.valueobject.Role.ADMIN
import com.kiva.domain.aggregate.user.model.valueobject.Role.CATALOG_MANAGER
import com.kiva.domain.aggregate.user.model.valueobject.Role.OPERATOR
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * A panel route that takes ADMIN and CATALOG_MANAGER, over kiva_test: the token lets the caller in, then the session
 * and the roles are read from the database on every request. Test phones start with 0990 and are deleted after each
 * test.
 */
class RequireRolesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2034-02-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db
    private val users = UserRepositoryImpl()
    private val sessions = AuthSessionRepositoryImpl()
    private val checkPanelAccess = CheckPanelAccessUc(ExposedTransactionManager(db), sessions, users, SecurityEventLogImpl(), clock)

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private class Caller(val user: User, val sessionId: SessionId, val token: String)

    /** A user with [roles] and a session started now, as a login would make it; [sessionRoles] decide the session's kind. */
    private fun caller(n: Int, vararg roles: Role, sessionRoles: Roles = Roles.of(roles.toList())): Caller = transaction(db) {
        val registered = users.findOrRegister(IranianMobileNumber.constructFromPersisted(AuthTestStack.phone(n)), clock.now).user
        if (roles.isNotEmpty()) users.save(registered.setPanelRoles(roles.toList(), by = UserId.SYSTEM).getOrNull() ?: error("refused"))
        val user = users.findById(registered.id) ?: error("not stored")
        val session = AuthSession.start(sessions.nextId(), user.id, sessionRoles, clock.now, SessionDevice.UNKNOWN)
        sessions.add(session, RandomSessionTokens().newRefreshToken().hash)
        Caller(user, session.id, stack.accessTokens.issue(user, session, clock.now).value)
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            configureAuthentication(stack.accessTokens, stack.accountCheck)
            routing {
                route(API_V1) {
                    requireRoles(checkPanelAccess, ADMIN, CATALOG_MANAGER) {
                        get("/admin/products") { call.respondText("products of ${call.accessPrincipal().userId.value}") }
                    }
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.open(token: String?): HttpResponse =
        client.get("$API_V1/admin/products") { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpResponse.code(): String? = Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    @Test
    fun `a role of the endpoint gets in, and another doesn't`() = withApp {
        assertEquals(HttpStatusCode.OK, open(caller(1, CATALOG_MANAGER).token).status)
        assertEquals(HttpStatusCode.OK, open(caller(2, ADMIN).token).status)

        val operator = open(caller(3, OPERATOR).token)
        assertEquals(HttpStatusCode.Forbidden, operator.status)
        assertEquals("FORBIDDEN", operator.code())
    }

    @Test
    fun `a customer is told no, 403, not sent to log in again`() = withApp {
        val customer = open(caller(4).token)

        assertEquals(HttpStatusCode.Forbidden, customer.status)
        assertEquals("FORBIDDEN", customer.code())
    }

    @Test
    fun `no token is 401, and an expired one is 401 TOKEN_EXPIRED`() = withApp {
        assertEquals("UNAUTHORIZED", open(token = null).code())

        val manager = caller(5, CATALOG_MANAGER)
        clock.advance(Duration.ofMinutes(16))
        assertEquals("TOKEN_EXPIRED", open(manager.token).code())
    }

    @Test
    fun `a session logged out or revoked shuts the panel at once, though its token is still valid`() = withApp {
        val manager = caller(6, CATALOG_MANAGER)
        assertEquals(HttpStatusCode.OK, open(manager.token).status)

        transaction(db) {
            AuthSessionTbl.update({ AuthSessionTbl.id eq manager.sessionId.value }) {
                it[revokedOn] = clock.now.atOffset(ZoneOffset.UTC)
                it[revokeReason] = RevokeReason.LOGOUT
            }
        }

        val after = open(manager.token)
        assertEquals(HttpStatusCode.Unauthorized, after.status)
        assertEquals("UNAUTHORIZED", after.code())
    }

    @Test
    fun `a role taken away shuts the panel at once, though the token still names it`() = withApp {
        val manager = caller(7, CATALOG_MANAGER)
        assertEquals(HttpStatusCode.OK, open(manager.token).status)

        transaction(db) { users.save(manager.user.setPanelRoles(emptyList(), by = UserId.SYSTEM).getOrNull() ?: error("refused")) }

        assertEquals(HttpStatusCode.Forbidden, open(manager.token).status)
    }

    @Test
    fun `a customer session can't reach the panel even after its user got a role, the next login can`() = withApp {
        // A role given without ending the user's sessions (a bug, or a change made by hand): the old session stays a
        // customer one.
        val promoted = caller(8, CATALOG_MANAGER, sessionRoles = Roles.CUSTOMER_ONLY)

        assertEquals(HttpStatusCode.Unauthorized, open(promoted.token).status)
    }

    @Test
    fun `a refusal by role is in the security log, with the path and the roles it needed`() = withApp {
        val operator = caller(9, OPERATOR)

        open(operator.token)

        val event = transaction(db) {
            SecurityEventTbl.selectAll()
                .where { (SecurityEventTbl.type eq "PANEL_ACCESS_DENIED") and (SecurityEventTbl.userId eq operator.user.id.value) }
                .single()
        }
        assertEquals(operator.sessionId.value, event[SecurityEventTbl.sessionId])
        assertEquals("/api/v1/admin/products", event[SecurityEventTbl.detail]["path"]?.jsonPrimitive?.content)
        assertEquals("ADMIN,CATALOG_MANAGER", event[SecurityEventTbl.detail]["requiredRoles"]?.jsonPrimitive?.content)
    }
}
