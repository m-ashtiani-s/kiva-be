package com.kiva.infrastructure.httpserver.auth

import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.Payload
import com.kiva.application.usecase.account.usecase.query.CheckAccountLiveUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.common.http.problem
import com.kiva.infrastructure.common.http.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.auth.principal
import io.ktor.server.response.header

/** The name of the access token check: `authenticate(ACCESS) { … }` around routes that need a login. */
const val ACCESS = "access"

/**
 * Who is calling, from a valid access token. The user's id goes explicitly into a use case's command; an endpoint of
 * `/me` never takes a user id from the path or the body.
 *
 * [roles] are as they were when the token was made. The panel doesn't trust them: it reads the roles from the
 * database on every request (requireRoles).
 */
data class AccessPrincipal(val userId: UserId, val sessionId: SessionId, val roles: Roles) {
    companion object {
        /**
         * From a token whose signature, issuer, audience and time were checked. Null (so a 401) when a claim isn't what
         * this server writes: `sub` and `sid` positive numbers, roles all known and CUSTOMER among them.
         */
        fun from(payload: Payload): AccessPrincipal? {
            val userId = payload.subject?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val sessionId = payload.getClaim(JwtAccessTokens.SESSION_CLAIM).asString()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val roleNames = payload.getClaim(JwtAccessTokens.ROLES_CLAIM).asList(String::class.java) ?: return null
            val roles = roleNames.map { name -> Role.entries.firstOrNull { it.name == name } ?: return null }
            if (Role.CUSTOMER !in roles) return null
            return AccessPrincipal(UserId.constructFromPersisted(userId), SessionId.constructFromPersisted(sessionId), Roles.of(roles))
        }
    }
}

/**
 * The access token check, as Ktor's `jwt` authentication with our verifier: the algorithm and key are fixed, then
 * issuer, audience and time. A refused token is a 401 problem that says why: TOKEN_EXPIRED when only its time ran out
 * (the client refreshes and tries again), UNAUTHORIZED for anything else (no token, a forged or broken one, or one of an
 * account deleted since: [isAccountLive] asks the database on every request, roadmap phase 14, decision 10).
 *
 * Where it applies:
 * - `authenticate(ACCESS)`: a login is needed (`/me`, checkout).
 * - `authenticate(ACCESS, optional = true)`: a guest is fine, but a token that was sent must be valid; an expired one is
 *   401, so the client refreshes instead of being treated as a guest without noticing.
 * - requireRoles: the panel, which is `authenticate(ACCESS)` plus a check of the session and roles in the database.
 */
fun Application.configureAuthentication(accessTokens: JwtAccessTokens, isAccountLive: suspend (UserId) -> Boolean) {
    install(Authentication) {
        jwt(ACCESS) {
            verifier(accessTokens.verifier)
            validate { credential -> AccessPrincipal.from(credential.payload)?.takeIf { isAccountLive(it.userId) } }
            challenge { _, _ -> call.respondAuthProblem(call.whyRejected(accessTokens.verifier)) }
        }
    }
}

/** The account check of [configureAuthentication], made of the use case that answers it. */
fun CheckAccountLiveUc.asAccountCheck(): suspend (UserId) -> Boolean = { userId -> execute(CheckAccountLiveUc.Qry(userId)) is Result.Success }

/** The caller of a route inside `authenticate(ACCESS)`. Outside one there is none: that is a bug in the route. */
fun ApplicationCall.accessPrincipal(): AccessPrincipal =
    principal<AccessPrincipal>() ?: error("No access principal: the route ${request.local.uri} is not inside authenticate(ACCESS)")

/** A 401 problem; [code] is UNAUTHORIZED or TOKEN_EXPIRED. */
suspend fun ApplicationCall.respondAuthProblem(code: String) {
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respondProblem(problem(HttpStatusCode.Unauthorized, code))
}

/**
 * Why the token was refused: the token is checked once more and the exception looked at. java-jwt checks the signature
 * before the time, so a forged token with an old `exp` is UNAUTHORIZED, never TOKEN_EXPIRED.
 */
private fun ApplicationCall.whyRejected(verifier: JWTVerifier): String {
    val header = runCatching { request.parseAuthorizationHeader() }.getOrNull()
    val token = (header as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob
        ?: return UNAUTHORIZED
    return try {
        verifier.verify(token)
        // Valid as a JWT, but its claims are not what this server writes.
        UNAUTHORIZED
    } catch (e: TokenExpiredException) {
        TOKEN_EXPIRED
    } catch (e: JWTVerificationException) {
        UNAUTHORIZED
    }
}

const val UNAUTHORIZED = "UNAUTHORIZED"
const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
