package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.application.port.SessionTokens
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.session.model.policy.SessionPolicy
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import java.time.Clock
import java.time.Duration

/**
 * Logs out (`POST /auth/logout`): ends the session of the refresh token cookie, or with [Cmd.allDevices] every session
 * of its user. It needs no access token, so it works after that one has expired.
 *
 * Always a success: there is nothing a client could do about "you were logged out already", and the route clears the
 * cookie whatever happens. Only the current token, or one rotated within the grace (another tab a moment ago), ends a
 * session; an older token only gets its cookie cleared, so a copied token can't be used to log its owner out.
 *
 * The access tokens already handed out stay valid for the rest of their 15 minutes; the client forgets them. The panel
 * checks its session on every request, so a staff member's logout ends their panel at once.
 */
class LogoutUc(
    private val transactionManager: TransactionManager,
    private val authSessionRepository: AuthSessionRepository,
    private val sessionTokens: SessionTokens,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<LogoutUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    /** @property refreshToken the `kiva_rt` cookie as it came, or null when there was none. */
    data class Cmd(val refreshToken: String?, val allDevices: Boolean, val ip: String?) {
        // The token is a secret.
        override fun toString(): String =
            "Cmd(refreshToken=${if (refreshToken == null) "none" else "<hidden>"}, allDevices=$allDevices, ip=$ip)"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("LogoutUc.execute allDevices=${command.allDevices} cookie=${if (command.refreshToken == null) "none" else "present"}")
        val token = command.refreshToken?.takeUnless { it.isBlank() } ?: return Result.success(Unit)

        val now = clock.instant()
        return transactionManager.inTransaction {
            val stored = authSessionRepository.findTokenForUpdate(sessionTokens.hashOf(token))
            val session = stored?.let { authSessionRepository.findByIdForUpdate(it.sessionId) }
            val rotatedOn = stored?.rotatedOn
            val usable = rotatedOn == null || Duration.between(rotatedOn, now) <= SessionPolicy.ROTATION_GRACE
            if (session == null || !usable || !session.isActiveAt(now)) {
                logger.info("LogoutUc: no live session behind the cookie; only the cookie is cleared")
                return@inTransaction Result.success(Unit)
            }

            val ended = if (command.allDevices) {
                authSessionRepository.revokeAllOf(session.userId, RevokeReason.LOGOUT_ALL, now)
            } else {
                authSessionRepository.save(session.revoke(RevokeReason.LOGOUT, now))
                1
            }
            val type = if (command.allDevices) SecurityEventType.LOGOUT_ALL else SecurityEventType.LOGOUT
            securityEventLog.record(
                SecurityEvent(type, now, userId = session.userId, sessionId = session.id, ip = command.ip, detail = mapOf("sessionsEnded" to ended)),
            )
            logger.info("LogoutUc: user ${session.userId.value} ended $ended session(s)")
            Result.success(Unit)
        }
    }
}
