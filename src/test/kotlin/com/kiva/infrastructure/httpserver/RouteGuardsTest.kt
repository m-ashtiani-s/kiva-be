package com.kiva.infrastructure.httpserver

import com.kiva.application.usecase.account.usecase.query.CheckAccountLiveUc
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.httpserver.auth.asAccountCheck
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.AuthConfig
import com.kiva.infrastructure.config.BlogConfig
import com.kiva.infrastructure.config.DatasourceConfig
import com.kiva.infrastructure.di.appModules
import com.kiva.infrastructure.httpserver.auth.RequiredRolesRouteSelector
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.KoinIsolated
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards that protect every route, those of later phases too: they walk the app's own routing, built as on boot, so a
 * route added without its login or with the wrong roles fails the build.
 *
 * 1. Every endpoint under `/api/v1/me` and `/api/v1/admin` refuses a call without a token (401). One that forgot
 *    `authenticate` answers something else and is named.
 * 2. Every endpoint under `/api/v1/admin` takes exactly the roles of its `x-roles` in the contract (api/kiva-openapi.yml),
 *    and an admin endpoint the contract doesn't have is an error. That requireRoles enforces the roles it is given is
 *    tested on its own (RequireRolesOnDatabaseTest).
 * 3. Every endpoint the app has whose contract operation says `security: [{ bearerAuth: [] }]` refuses a call without a
 *    token, wherever it lives: `/checkout`, `/orders`, `/payments/...` and `/cart/merge` are covered the day they exist.
 * 4. Every endpoint under `/api/v1/admin` refuses a real panel session whose roles aren't among its own (403): a staff
 *    member of another panel role, or a plain customer where every staff role is allowed.
 */
class RouteGuardsTest {

    // Only the database connection is used; the datasource settings are read at boot, before Koin.
    private val appConfig = AppConfig(
        DatasourceConfig("jdbc:postgresql://localhost:5432/kiva_test", "unused", "unused", 1),
        auth = AuthConfig(otpPepper = ByteArray(32) { 7 }, jwtSecret = ByteArray(32) { 8 }, maxSmsPerHour = 300),
        blog = BlogConfig(voterPepper = ByteArray(32) { 9 }),
    )

    private data class Endpoint(val method: HttpMethod, val path: String, val requiredRoles: Set<Role>?) {
        override fun toString() = "${method.value} $path"
    }

    /** The app's routing as boot builds it (Koin of its own, so tests don't share one), and every endpoint in it. */
    private fun withTheApp(block: suspend ApplicationTestBuilder.(List<Endpoint>) -> Unit) = withTheAppAndTokens { endpoints, _ -> block(endpoints) }

    /** [withTheApp], plus the app's own token signer, so a test can log in a user it made. */
    private fun withTheAppAndTokens(block: suspend ApplicationTestBuilder.(List<Endpoint>, JwtAccessTokens) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        var endpoints = emptyList<Endpoint>()
        lateinit var tokens: JwtAccessTokens
        application {
            this.install(KoinIsolated) { modules(appModules(appConfig, TestDatabase.database)) }
            configureHttp()
            tokens = get()
            configureAuthentication(get(), get<CheckAccountLiveUc>().asAccountCheck())
            configureRouting()
            endpoints = endpointsOf(this)
        }
        startApplication()
        block(endpoints, tokens)
    }

    private fun endpointsOf(application: Application): List<Endpoint> =
        application.plugin(RoutingRoot).getAllRoutes().mapNotNull { it.toEndpoint() }

    private fun RoutingNode.toEndpoint(): Endpoint? {
        val method = (selector as? HttpMethodRouteSelector)?.method ?: return null
        val fromRoot = generateSequence(this) { it.parent }.toList().reversed()
        val path = fromRoot.mapNotNull { node ->
            when (val selector = node.selector) {
                is PathSegmentConstantRouteSelector -> selector.value
                is PathSegmentParameterRouteSelector -> "{${selector.name}}"
                else -> null
            }
        }.joinToString("/", prefix = "/")
        val roles = fromRoot.firstNotNullOfOrNull { (it.selector as? RequiredRolesRouteSelector)?.roles }
        return Endpoint(method, path, roles)
    }

    private fun String.isUnder(prefix: String) = this == prefix || startsWith("$prefix/")

    @Test
    fun `every endpoint under me and admin refuses a call without a token`() = withTheApp { endpoints ->
        val guarded = endpoints.filter { it.path.isUnder("$API_V1/me") || it.path.isUnder("$API_V1/admin") }
        assertTrue(Endpoint(HttpMethod.Get, "$API_V1/me", null) in guarded, "GET /me not found, so the walk is broken: $endpoints")

        val open = guarded.filter { answersWithoutLogin(it) }

        assertEquals(emptyList(), open, "These answer without a login")
    }

    @Test
    fun `every endpoint the contract says needs a login refuses a call without a token`() = withTheApp { endpoints ->
        val needLogin = contractBearerOperations()
        assertTrue("GET /me/addresses" in needLogin && "POST /orders" in needLogin, "Read only $needLogin; the parser is broken")
        assertTrue("GET /cart" !in needLogin, "GET /cart takes an optional login, so it must not be read as needing one")

        val guarded = endpoints.filter { "${it.method.value} ${it.path.removePrefix(API_V1)}" in needLogin }
        assertTrue(Endpoint(HttpMethod.Delete, "$API_V1/me/addresses/{addressId}", null) in guarded, "The address routes weren't found: $guarded")

        val open = guarded.filter { answersWithoutLogin(it) }

        assertEquals(emptyList(), open, "These answer without a login")
    }

    /** Calls [endpoint] with no Authorization header (a JSON `{}` body where it takes one); true unless it answers 401. */
    private suspend fun ApplicationTestBuilder.answersWithoutLogin(endpoint: Endpoint): Boolean {
        val response = client.request(endpoint.path.replace(Regex("\\{[^}]+}"), "1")) {
            method = endpoint.method
            if (endpoint.method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
        return response.status != HttpStatusCode.Unauthorized
    }

    @Test
    fun `every admin endpoint takes exactly the roles of its x-roles in the contract`() = withTheApp { endpoints ->
        val contract = contractRoles()

        val wrong = endpoints.filter { it.path.isUnder("$API_V1/admin") }.mapNotNull { endpoint ->
            val operation = "${endpoint.method.value} ${endpoint.path.removePrefix(API_V1)}"
            val expected = contract[operation] ?: return@mapNotNull "$operation is not in the contract"
            when (endpoint.requiredRoles) {
                expected -> null
                null -> "$operation has no requireRoles"
                else -> "$operation takes ${endpoint.requiredRoles}, the contract says $expected"
            }
        }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `every admin endpoint refuses a panel session without one of its roles`() = withTheAppAndTokens { endpoints, tokens ->
        val admin = endpoints.filter { it.path.isUnder("$API_V1/admin") && it.requiredRoles != null }
        assertTrue(admin.any { it.path == "$API_V1/admin/customers/{customerId}/roles" }, "The admin routes weren't found: $endpoints")
        val users = UserRepositoryImpl()
        val sessions = AuthSessionRepositoryImpl()
        val staff = mutableMapOf<Role, String>()
        fun tokenOf(role: Role): String = staff.getOrPut(role) {
            transaction(TestDatabase.database) {
                val now = Instant.now()
                val phone = "09900" + (900 + Role.entries.indexOf(role)).toString().padStart(6, '0')
                var user = users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone), now).user
                val roles = if (role == Role.CUSTOMER) emptyList() else listOf(role)
                if (roles.isNotEmpty()) user = user.setPanelRoles(roles, by = UserId.SYSTEM).getOrNull() ?: error("refused")
                users.save(user)
                val stored = users.findById(user.id) ?: error("not stored")
                val session = AuthSession.start(sessions.nextId(), stored.id, Roles.of(roles), now, SessionDevice.UNKNOWN)
                sessions.add(session, RandomSessionTokens().newRefreshToken().hash)
                tokens.issue(stored, session, now).value
            }
        }

        try {
            val letIn = admin.mapNotNull { endpoint ->
                val outsider = (Role.entries - Role.ADMIN - Role.CUSTOMER - endpoint.requiredRoles.orEmpty()).firstOrNull() ?: Role.CUSTOMER
                val response = client.request(endpoint.path.replace(Regex("\\{[^}]+}"), "1")) {
                    method = endpoint.method
                    header(HttpHeaders.Authorization, "Bearer ${tokenOf(outsider)}")
                    if (endpoint.method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                }
                if (response.status == HttpStatusCode.Forbidden) null else "$endpoint let $outsider in (${response.status})"
            }

            assertEquals(emptyList(), letIn)
        } finally {
            transaction(TestDatabase.database) {
                val ids = UserTbl.select(UserTbl.id).where { UserTbl.phone like "0990000090%" }.map { it[UserTbl.id].value }
                val sessionIds = AuthSessionTbl.select(AuthSessionTbl.id).where { AuthSessionTbl.userId inList ids }.map { it[AuthSessionTbl.id].value }
                RefreshTokenTbl.deleteWhere { RefreshTokenTbl.sessionId inList sessionIds }
                AuthSessionTbl.deleteWhere { AuthSessionTbl.userId inList ids }
                UserTbl.deleteWhere { UserTbl.id inList ids }
            }
        }
    }

    @Test
    fun `the contract is read right, and the walk sees a panel route's roles and its path`() = testApplication {
        val contract = contractRoles()
        assertTrue(contract.size > 20, "Read only ${contract.size} operations with x-roles; the parser is broken")
        assertEquals(setOf(Role.ADMIN), contract["PUT /admin/customers/{customerId}/roles"])

        environment { config = MapApplicationConfig() }
        var endpoints = emptyList<Endpoint>()
        application {
            val db = TestDatabase.database
            val checkPanelAccess = CheckPanelAccessUc(
                ExposedTransactionManager(db), AuthSessionRepositoryImpl(), UserRepositoryImpl(), SecurityEventLogImpl(), MutableClock(Instant.EPOCH),
            )
            configureAuthentication(JwtAccessTokens(ByteArray(32) { 8 })) { true }
            routing { route(API_V1) { requireRoles(checkPanelAccess, Role.ADMIN) { put("/admin/customers/{customerId}/roles") {} } } }
            endpoints = endpointsOf(this)
        }
        startApplication()

        assertEquals(listOf(Endpoint(HttpMethod.Put, "$API_V1/admin/customers/{customerId}/roles", setOf(Role.ADMIN))), endpoints)
    }

    /** `METHOD /path` to its x-roles, for every operation of the contract that has them. */
    private fun contractRoles(): Map<String, Set<Role>> {
        val roles = mutableMapOf<String, Set<Role>>()
        var path: String? = null
        var method: String? = null
        File("api/kiva-openapi.yml").readLines().forEach { line ->
            PATH.matchEntire(line)?.let {
                path = it.groupValues[1]
                method = null
            }
            METHOD.matchEntire(line)?.let { method = it.groupValues[1].uppercase() }
            X_ROLES.matchEntire(line)?.let { match ->
                val operation = "$method $path"
                roles[operation] = match.groupValues[1].split(',').map { Role.valueOf(it.trim()) }.toSet()
            }
        }
        return roles
    }

    /**
     * `METHOD /path` of every operation of the contract that needs a login: `security: [{ bearerAuth: [] }]` exactly.
     * An optional login (`[{}, { bearerAuth: [] }]`) doesn't count.
     */
    private fun contractBearerOperations(): Set<String> {
        val operations = mutableSetOf<String>()
        var path: String? = null
        var method: String? = null
        File("api/kiva-openapi.yml").readLines().forEach { line ->
            PATH.matchEntire(line)?.let {
                path = it.groupValues[1]
                method = null
            }
            METHOD.matchEntire(line)?.let { method = it.groupValues[1].uppercase() }
            if (BEARER_ONLY.matches(line)) operations += "$method $path"
        }
        return operations
    }

    private companion object {
        val PATH = Regex("^ {2}(/\\S*):\\s*$")
        val METHOD = Regex("^ {4}(get|post|put|patch|delete):\\s*$")
        val X_ROLES = Regex("^ {6}x-roles:\\s*\\[(.*)]\\s*$")
        val BEARER_ONLY = Regex("^ {6}security:\\s*\\[\\{\\s*bearerAuth:\\s*\\[]\\s*}]\\s*$")
    }
}
