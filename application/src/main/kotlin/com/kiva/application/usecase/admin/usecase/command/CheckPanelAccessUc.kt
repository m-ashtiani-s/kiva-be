package com.kiva.application.usecase.admin.usecase.command

import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * The guard of every panel request (every `/admin` endpoint): run on each one, after its access token was checked.
 *
 * The token alone is not trusted for the panel. Its session and the user's roles are read from the database every
 * time, so a staff member who logs out, or whose role is taken away, loses the panel at once, not when the token's 15
 * minutes are up. In this order:
 * - the session must be the token's user's and still active; otherwise 401 (log in again)
 * - the user's current roles must share one with the endpoint's `x-roles`; otherwise 403
 * - the session must be a PANEL session; otherwise 401
 *
 * A command, not a query, because a refusal by role is written to the security log (PANEL_ACCESS_DENIED): someone
 * trying panel endpoints they may not use is worth seeing. The refusal is a successful outcome inside the
 * transaction, so the event is committed, and a failure only after it.
 *
 * A customer session can't reach the panel even if its user has a panel role meanwhile: roles change only through a
 * change that ends all of the user's sessions, so such a session is a leftover, and the next login makes a PANEL one.
 */
class CheckPanelAccessUc(
    private val transactionManager: TransactionManager,
    private val authSessionRepository: AuthSessionRepository,
    private val userRepository: UserRepository,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<CheckPanelAccessUc.Cmd, Unit, CheckPanelAccessUc.Failure> {

    private val logger = getLogger()

    /**
     * @property sessionId, userId from the access token, already checked for its signature and time.
     * @property allowed the endpoint's `x-roles`.
     * @property path what was asked for, for the security log.
     */
    data class Cmd(val sessionId: SessionId, val userId: UserId, val allowed: Set<Role>, val path: String)

    private enum class Outcome { ALLOWED, SESSION_GONE, NOT_ALLOWED }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("CheckPanelAccessUc.execute user=${command.userId.value} session=${command.sessionId.value} path=${command.path}")
        val now = clock.instant()

        val outcome = transactionManager.inTransaction<Outcome, Nothing> {
            val session = authSessionRepository.findById(command.sessionId)
            val sessionLive = session != null && session.userId == command.userId && session.isActiveAt(now)
            val user = if (sessionLive) userRepository.findById(command.userId) else null
            when {
                session == null || user == null -> Result.success(Outcome.SESSION_GONE)
                // Roles before the kind of session: a customer who opens the panel is told no (403), not sent to log
                // in again (401) over and over.
                user.roles.values.none { it in command.allowed } -> {
                    securityEventLog.record(
                        SecurityEvent(
                            SecurityEventType.PANEL_ACCESS_DENIED, now, userId = command.userId, sessionId = command.sessionId,
                            detail = mapOf("path" to command.path, "requiredRoles" to command.allowed.sorted().joinToString(",")),
                        ),
                    )
                    Result.success(Outcome.NOT_ALLOWED)
                }
                session.kind != SessionKind.PANEL -> Result.success(Outcome.SESSION_GONE)
                else -> Result.success(Outcome.ALLOWED)
            }
        }.unwrapOr { it }

        return when (outcome) {
            Outcome.ALLOWED -> Result.success(Unit)
            Outcome.SESSION_GONE -> {
                logger.info("CheckPanelAccessUc: session ${command.sessionId.value} is not a live panel session")
                Result.failure(Failure.SessionGone)
            }
            Outcome.NOT_ALLOWED -> {
                logger.info("CheckPanelAccessUc: user ${command.userId.value} may not use ${command.path}")
                Result.failure(Failure.NotAllowed)
            }
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Logged out, revoked, run out of time, or not a panel session: log in again. */
        data object SessionGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)

        /** None of the user's roles may use this endpoint. */
        data object NotAllowed : Failure("FORBIDDEN", FailureKind.FORBIDDEN)
    }
}
