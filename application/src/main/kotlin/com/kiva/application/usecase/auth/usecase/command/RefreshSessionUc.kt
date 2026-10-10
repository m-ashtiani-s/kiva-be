package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.dto.IssuedSession
import com.kiva.application.port.AccessTokens
import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.application.port.SessionTokens
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.entity.AuthSession.Refresh
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A new access token for the session of the refresh token cookie (`POST /auth/refresh`), what the client does silently
 * every 15 minutes. What happens is the session's to decide ([AuthSession.refreshWith]):
 * - the current token is rotated: a new cookie, the old token marked rotated, the session's idle end moved forward
 * - a token rotated in the last 30 seconds (a second tab at the same moment) gets an access token and no new cookie
 * - a token rotated longer ago was copied: the session is revoked, so the thief's and the owner's tokens both stop,
 *   and REFRESH_REUSE_DETECTED goes to the security log
 * - anything else (no cookie, an unknown token, a session ended or out of time) is 401 REFRESH_TOKEN_INVALID
 *
 * The access token carries the user's roles as they are now in the database, not as they were at login.
 *
 * Revoking a stolen session is answered with a 401, and must still be committed: so it is a successful [Outcome]
 * inside the transaction, and a failure only after it (the same trap as a wrong code).
 */
class RefreshSessionUc(
    private val transactionManager: TransactionManager,
    private val authSessionRepository: AuthSessionRepository,
    private val userRepository: UserRepository,
    private val sessionTokens: SessionTokens,
    private val accessTokens: AccessTokens,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<RefreshSessionUc.Cmd, IssuedSession, RefreshSessionUc.Failure> {

    private val logger = getLogger()

    /** @property refreshToken the `kiva_rt` cookie as it came, or null when there was none. */
    data class Cmd(val refreshToken: String?, val ip: String?) {
        // The token is a secret.
        override fun toString(): String = "Cmd(refreshToken=${if (refreshToken == null) "none" else "<hidden>"}, ip=$ip)"
    }

    private sealed interface Outcome {
        class Refreshed(val session: IssuedSession) : Outcome

        data object Refused : Outcome
    }

    override suspend fun execute(command: Cmd): Result<IssuedSession, Failure> {
        val token = command.refreshToken?.takeUnless { it.isBlank() }
        logger.info("RefreshSessionUc.execute cookie=${if (token == null) "none" else "present"} ip=${command.ip}")
        if (token == null) return Result.failure(Failure.Invalid)

        val now = clock.instant()
        val outcome = transactionManager.inTransaction<Outcome, Nothing> {
            Result.success(refresh(token, command.ip, now))
        }.unwrapOr { it }

        return when (outcome) {
            is Outcome.Refreshed -> Result.success(outcome.session)
            Outcome.Refused -> Result.failure(Failure.Invalid)
        }
    }

    private fun refresh(rawToken: String, ip: String?, now: Instant): Outcome {
        val token = authSessionRepository.findTokenForUpdate(sessionTokens.hashOf(rawToken)) ?: return refused("unknown token")
        val session = authSessionRepository.findByIdForUpdate(token.sessionId) ?: return refused("no session")

        return when (val refresh = session.refreshWith(token, now)) {
            Refresh.Refused -> refused("session ${session.id.value} ended")
            is Refresh.Rotate -> {
                val next = sessionTokens.newRefreshToken()
                authSessionRepository.rotate(token, next.hash, now)
                authSessionRepository.save(refresh.session)
                issue(refresh.session, next.token, now) ?: refused("user ${session.userId.value} is gone")
            }
            Refresh.WithinGrace -> issue(session, refreshToken = null, now) ?: refused("user ${session.userId.value} is gone")
            is Refresh.Reused -> {
                authSessionRepository.save(refresh.session)
                securityEventLog.record(
                    SecurityEvent(
                        SecurityEventType.REFRESH_REUSE_DETECTED, now, userId = session.userId, sessionId = session.id, ip = ip,
                        detail = mapOf("rotatedSecondsAgo" to refresh.sinceRotation.seconds),
                    ),
                )
                logger.warn("RefreshSessionUc: a token of session ${session.id.value} was used ${refresh.sinceRotation.seconds} s after its rotation; the session is revoked")
                Outcome.Refused
            }
        }
    }

    /** An access token with the user's roles as they are now; null if the user no longer exists. */
    private fun issue(session: AuthSession, refreshToken: String?, now: Instant): Outcome.Refreshed? {
        val user = userRepository.findById(session.userId) ?: return null
        val accessToken = accessTokens.issue(user, session, now)
        return Outcome.Refreshed(
            IssuedSession(
                userId = user.id,
                isNewUser = false,
                accessToken = accessToken.value,
                accessTokenExpiresIn = accessToken.expiresIn,
                refreshToken = refreshToken,
                refreshTokenMaxAge = Duration.between(now, session.idleExpiresOn),
            ),
        )
    }

    private fun refused(why: String): Outcome {
        logger.info("RefreshSessionUc: refused, $why")
        return Outcome.Refused
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** No usable session behind the cookie: log in again. */
        data object Invalid : Failure("REFRESH_TOKEN_INVALID", FailureKind.UNAUTHORIZED)
    }
}
