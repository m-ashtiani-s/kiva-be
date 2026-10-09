package com.kiva.domain.aggregate.session.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.policy.SessionPolicy
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import java.time.Instant

/**
 * One login: from a successful code until it is logged out, revoked, or runs out of time.
 *
 * Stored in a plain table. Its refresh tokens are kept apart (only their hashes), one current at a time; the access
 * tokens are not stored at all, they carry the session's id (`sid`).
 *
 * It ends when it is revoked, when it isn't refreshed for its idle lifetime, or at its absolute end, whichever comes
 * first ([SessionPolicy]). The idle end never goes past the absolute one.
 *
 * Immutable: every change returns a new AuthSession, which the use case saves.
 */
class AuthSession private constructor(
    val id: SessionId,
    val userId: UserId,
    val kind: SessionKind,
    val createdOn: Instant,
    val lastUsedOn: Instant,
    val idleExpiresOn: Instant,
    val absoluteExpiresOn: Instant,
    val revokedOn: Instant?,
    val revokeReason: RevokeReason?,
    val device: SessionDevice,
) {
    init {
        require(!idleExpiresOn.isAfter(absoluteExpiresOn)) { "Session ${id.value}: the idle end is after the absolute end" }
        require((revokedOn == null) == (revokeReason == null)) { "Session ${id.value}: a revoke needs its reason, and only a revoke" }
    }

    /** Not revoked and not run out of time: its refresh token can still be used. */
    fun isActiveAt(now: Instant): Boolean = revokedOn == null && now.isBefore(idleExpiresOn) && now.isBefore(absoluteExpiresOn)

    override fun equals(other: Any?): Boolean = other is AuthSession && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "AuthSession(id=${id.value}, user=${userId.value}, kind=$kind)"

    companion object {
        /**
         * A new session of a user who just logged in. Its kind comes from [roles] ([SessionKind.of]) and decides both
         * of its ends.
         *
         * [id] is handed out before (a database sequence). [userId] is a real account: the system never logs in.
         */
        fun start(id: SessionId, userId: UserId, roles: Roles, now: Instant, device: SessionDevice): AuthSession {
            require(userId != UserId.SYSTEM) { "UserId.SYSTEM never logs in" }
            val kind = SessionKind.of(roles)
            val absoluteEnd = now.plus(SessionPolicy.absoluteLifetime(kind))
            return AuthSession(
                id = id,
                userId = userId,
                kind = kind,
                createdOn = now,
                lastUsedOn = now,
                idleExpiresOn = minOf(now.plus(SessionPolicy.idleLifetime(kind)), absoluteEnd),
                absoluteExpiresOn = absoluteEnd,
                revokedOn = null,
                revokeReason = null,
                device = device,
            )
        }

        fun constructFromPersisted(
            id: SessionId,
            userId: UserId,
            kind: SessionKind,
            createdOn: Instant,
            lastUsedOn: Instant,
            idleExpiresOn: Instant,
            absoluteExpiresOn: Instant,
            revokedOn: Instant?,
            revokeReason: RevokeReason?,
            device: SessionDevice,
        ): AuthSession = AuthSession(id, userId, kind, createdOn, lastUsedOn, idleExpiresOn, absoluteExpiresOn, revokedOn, revokeReason, device)
    }
}
