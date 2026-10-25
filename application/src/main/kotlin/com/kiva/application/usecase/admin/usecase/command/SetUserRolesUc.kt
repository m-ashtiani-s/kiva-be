package com.kiva.application.usecase.admin.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zipAll
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * An admin sets a user's panel roles (`PUT /admin/customers/{customerId}/roles`, ADMIN only): the list replaces the
 * old one, and CUSTOMER stays whether it is in it or not.
 *
 * After a change, every session of that user ends (ROLES_CHANGED): their next login gets the new roles and the right
 * kind of session (PANEL for staff). The change goes to the security log with who made it and the roles before and
 * after, and to the panel's change log (USER_ROLES_CHANGED, the roles before and after). The same roles again change
 * nothing, end nothing and log nothing.
 *
 * Nobody changes their own roles (422 ROLE_CHANGE_NOT_ALLOWED), so the last admin can't lock themselves out by mistake,
 * and nobody takes ADMIN from the last one (422 ROLE_CHANGE_NOT_ALLOWED, `meta.reason = LAST_ADMIN`). Changes of roles
 * happen one at a time (the roles lock, section 4.11 lock 0), and under it the admin's own roles are read again: two
 * admins taking ADMIN from each other at once, the second finds they are no longer one (403 FORBIDDEN), as the route's
 * check at the start of the request couldn't know (roadmap phase 4, decision 14).
 *
 * @return the user whose roles were set.
 */
class SetUserRolesUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val securityEventLog: SecurityEventLog,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<SetUserRolesUc.Cmd, UserId, SetUserRolesUc.Failure> {

    private val logger = getLogger()

    /**
     * @property customerId the path's `{customerId}` as it came.
     * @property roles the body's `roles`, as sent.
     * @property actor the admin, from the access token and the request.
     */
    data class Cmd(val customerId: String, val roles: List<String>, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<UserId, Failure> {
        logger.info("SetUserRolesUc.execute customer=${command.customerId} roles=${command.roles} by=${command.actor.userId.value}")

        val customerId = command.customerId.toLongOrNull()?.let { UserId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)
        val roles = Result.zipAll(command.roles.mapIndexed { index, raw -> Role.makeNew(raw).forField("roles.$index") }, ::InputProblems)
            .unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            userRepository.lockRoleChanges()
            val actor = userRepository.findById(command.actor.userId)
            if (actor == null || Role.ADMIN !in actor.roles) {
                logger.info("SetUserRolesUc: user ${command.actor.userId.value} is no longer an ADMIN")
                return@inTransaction Result.failure(Failure.NoLongerAdmin)
            }
            // A deleted account is no customer any more (roadmap phase 14, decision 12): it gets no role.
            val user = userRepository.findById(customerId)?.takeUnless { it.isDeleted } ?: return@inTransaction Result.failure(Failure.NotFound)
            val updated = user.setPanelRoles(roles, command.actor.userId)
                .unwrapOr { return@inTransaction Result.failure(Failure.OwnRoles) }
            if (Role.ADMIN in user.roles && Role.ADMIN !in updated.roles && userRepository.countAdmins() <= 1) {
                return@inTransaction Result.failure(Failure.LastAdmin)
            }
            if (updated.roles != user.roles) recordChange(user, updated, command.actor)
            Result.success(user.id)
        }
    }

    private fun recordChange(before: User, after: User, actor: PanelActor) {
        val now = clock.instant()
        val by = actor.userId
        userRepository.save(after)
        val sessionsEnded = authSessionRepository.revokeAllOf(after.id, RevokeReason.ROLES_CHANGED, now)
        securityEventLog.record(
            SecurityEvent(
                SecurityEventType.ROLES_CHANGED, now, userId = after.id,
                detail = mapOf(
                    "by" to by.value,
                    "from" to before.roles.values.joinToString(","),
                    "to" to after.roles.values.joinToString(","),
                    "sessionsEnded" to sessionsEnded,
                ),
            ),
        )
        auditTrail.record(
            AuditEntry(
                at = now,
                actor = actor,
                action = AuditAction.USER_ROLES_CHANGED,
                entityType = AuditEntityType.USER,
                entityId = after.id.value.toString(),
                entityLabel = listOfNotNull(after.firstName?.value, after.lastName?.value).joinToString(" ").ifEmpty { null },
                changes = AuditDiff.build { list("roles", before.roles.values.map { it.name }.sorted(), after.roles.values.map { it.name }.sorted()) },
            ),
        )
        logger.info("SetUserRolesUc: user ${after.id.value} is now ${after.roles.values}; $sessionsEnded session(s) ended")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        /** A role that isn't one of the contract's, each under its place in the list (`roles.1`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data object OwnRoles : Failure("ROLE_CHANGE_NOT_ALLOWED", FailureKind.BUSINESS_RULE)

        /** The change would leave no ADMIN at all. */
        data object LastAdmin : Failure("ROLE_CHANGE_NOT_ALLOWED", FailureKind.BUSINESS_RULE, meta = mapOf("reason" to "LAST_ADMIN"))

        /** The one changing roles lost ADMIN since the request began (another admin just took it). */
        data object NoLongerAdmin : Failure("FORBIDDEN", FailureKind.FORBIDDEN)
    }
}
