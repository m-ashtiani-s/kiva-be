package com.kiva.infrastructure.httpserver.auth

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.common.railway.onFailure
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondFailure
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/**
 * A panel endpoint: a login is needed, and on every request [CheckPanelAccessUc] reads the session and the user's
 * roles from the database. [roles] are the endpoint's `x-roles` in the contract; a test checks every `/admin` route's
 * roles against it.
 *
 * It includes `authenticate(ACCESS)`, so a panel route can't be left without a login by forgetting it:
 * ```kotlin
 * requireRoles(checkPanelAccessUc, Role.ADMIN, Role.CATALOG_MANAGER) {
 *     get("/admin/products") { … }
 * }
 * ```
 */
fun Route.requireRoles(checkPanelAccessUc: CheckPanelAccessUc, vararg roles: Role, build: Route.() -> Unit): Route {
    require(roles.isNotEmpty()) { "A panel route needs at least one role" }
    val allowed = roles.toSet()
    return authenticate(ACCESS) {
        val guarded = createChild(RequiredRolesRouteSelector(allowed))
        guarded.install(RequireRoles) {
            this.roles = allowed
            this.checkPanelAccessUc = checkPanelAccessUc
        }
        guarded.build()
    }
}

/** Marks where requireRoles starts in the route tree, and which roles it lets in; it matches every request. */
class RequiredRolesRouteSelector(val roles: Set<Role>) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(roles ${roles.sorted().joinToString()})"
}

class RequireRolesConfig {
    var roles: Set<Role> = emptySet()
    var checkPanelAccessUc: CheckPanelAccessUc? = null
}

private val RequireRoles = createRouteScopedPlugin("RequireRoles", ::RequireRolesConfig) {
    val roles = pluginConfig.roles
    val checkPanelAccessUc = checkNotNull(pluginConfig.checkPanelAccessUc) { "requireRoles needs CheckPanelAccessUc" }

    on(AuthenticationChecked) { call ->
        // The token was refused: authenticate has answered already.
        if (call.isHandled) return@on
        // Never let a panel request through without a caller, whatever the routes around it.
        val principal = call.principal<AccessPrincipal>() ?: return@on call.respondAuthProblem(UNAUTHORIZED)

        checkPanelAccessUc.execute(CheckPanelAccessUc.Cmd(principal.sessionId, principal.userId, roles, call.request.path()))
            .onFailure { call.respondFailure(it) }
    }
}
