package com.kiva.infrastructure.httpserver

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.AuthConfig
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
 */
class RouteGuardsTest {

    // Only the database connection is used; the datasource settings are read at boot, before Koin.
    private val appConfig = AppConfig(
        DatasourceConfig("jdbc:postgresql://localhost:5432/kiva_test", "unused", "unused", 1),
        auth = AuthConfig(otpPepper = ByteArray(32) { 7 }, jwtSecret = ByteArray(32) { 8 }, maxSmsPerHour = 300),
    )

    private data class Endpoint(val method: HttpMethod, val path: String, val requiredRoles: Set<Role>?) {
        override fun toString() = "${method.value} $path"
    }

    /** The app's routing as boot builds it (Koin of its own, so tests don't share one), and every endpoint in it. */
    private fun withTheApp(block: suspend ApplicationTestBuilder.(List<Endpoint>) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        var endpoints = emptyList<Endpoint>()
        application {
            this.install(KoinIsolated) { modules(appModules(appConfig, TestDatabase.database)) }
            configureHttp()
            configureAuthentication(get())
            configureRouting()
            endpoints = endpointsOf(this)
        }
        startApplication()
        block(endpoints)
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

        val open = guarded.filter { endpoint ->
            val response = client.request(endpoint.path.replace(Regex("\\{[^}]+}"), "1")) {
                method = endpoint.method
                if (endpoint.method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
            response.status != HttpStatusCode.Unauthorized
        }

        assertEquals(emptyList(), open, "These answer without a login")
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
            configureAuthentication(JwtAccessTokens(ByteArray(32) { 8 }))
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

    private companion object {
        val PATH = Regex("^ {2}(/\\S*):\\s*$")
        val METHOD = Regex("^ {4}(get|post|put|patch|delete):\\s*$")
        val X_ROLES = Regex("^ {6}x-roles:\\s*\\[(.*)]\\s*$")
    }
}
