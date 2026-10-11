package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * Makes the very first admin, on startup, from the phone in `KIVA_BOOTSTRAP_ADMIN_PHONE`: no endpoint can make an
 * admin out of nothing, so the first one comes from whoever controls the server's settings.
 *
 * Only while no ADMIN exists at all. Once one does, the setting is ignored (with a line in the log saying so, so it gets
 * removed), and a restart changes nothing. The phone's account is made if it has none; it keeps any roles it had,
 * gets ADMIN, and its sessions end so the next login is a panel session. ADMIN_BOOTSTRAPPED goes to the security log.
 *
 * Two servers starting at the same moment can both see no admin; both then give ADMIN to the same phone, which is the
 * same result.
 *
 * @return the new admin, or null when there was one already.
 */
class BootstrapAdminUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<BootstrapAdminUc.Cmd, UserId?, Nothing> {

    private val logger = getLogger()

    data class Cmd(val phone: IranianMobileNumber)

    override suspend fun execute(command: Cmd): Result<UserId?, Nothing> {
        logger.info("BootstrapAdminUc.execute phone=${command.phone.masked()}")
        val now = clock.instant()

        val made = transactionManager.inTransaction<UserId?, Nothing> {
            if (userRepository.hasAnyAdmin()) return@inTransaction Result.success(null)

            val (user, isNew) = userRepository.findOrRegister(command.phone, now)
            // By the system, not by a person: setPanelRoles refuses only a user changing their own roles.
            val admin = user.setPanelRoles(user.roles.values + Role.ADMIN, by = UserId.SYSTEM).unwrapOr { error("refused: $it") }
            userRepository.save(admin)
            authSessionRepository.revokeAllOf(admin.id, RevokeReason.ROLES_CHANGED, now)
            securityEventLog.record(
                SecurityEvent(SecurityEventType.ADMIN_BOOTSTRAPPED, now, userId = admin.id, phone = command.phone, detail = mapOf("newAccount" to isNew)),
            )
            Result.success(admin.id)
        }.unwrapOr { it }

        if (made == null) {
            logger.info("BootstrapAdminUc: an ADMIN exists already, so KIVA_BOOTSTRAP_ADMIN_PHONE is ignored; remove it")
        } else {
            logger.warn("BootstrapAdminUc: ${command.phone.masked()} (user ${made.value}) is now ADMIN; remove KIVA_BOOTSTRAP_ADMIN_PHONE")
        }
        return Result.success(made)
    }
}
