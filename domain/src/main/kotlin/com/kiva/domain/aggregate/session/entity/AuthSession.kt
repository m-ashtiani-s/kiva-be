package com.kiva.domain.aggregate.session.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.policy.SessionPolicy
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import java.time.Duration
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

    /**
     * A browser sent back [token], one of this session's; what to do with it (section 6 of the login plan):
     * - the session is revoked or out of time: [Refresh.Refused]
     * - the current token: [Refresh.Rotate], with the session used now and its idle end moved forward (never past its
     *   absolute end); the caller rotates the token and adds the next one
     * - a token rotated at most [SessionPolicy.ROTATION_GRACE] ago: [Refresh.WithinGrace], a second tab that refreshed at
     *   the same moment; it gets an access token, and the cookie stays as the first tab set it
     * - a token rotated longer ago: it was copied. [Refresh.Reused], with the session revoked: the thief's token and the
     *   owner's new one stop working alike, and the owner logs in again
     */
    fun refreshWith(token: RefreshToken, now: Instant): Refresh {
        require(token.sessionId == id) { "Token of session ${token.sessionId.value} tried on session ${id.value}" }
        if (!isActiveAt(now)) return Refresh.Refused
        val rotatedOn = token.rotatedOn ?: return Refresh.Rotate(copy(lastUsedOn = now, idleExpiresOn = idleEndFrom(now)))
        val sinceRotation = Duration.between(rotatedOn, now)
        return if (sinceRotation <= SessionPolicy.ROTATION_GRACE) {
            Refresh.WithinGrace
        } else {
            Refresh.Reused(revoke(RevokeReason.REUSE_DETECTED, now), sinceRotation)
        }
    }

    /** Ends the session now. A session revoked already stays as it was, with its first reason. */
    fun revoke(reason: RevokeReason, now: Instant): AuthSession =
        if (revokedOn != null) this else copy(revokedOn = now, revokeReason = reason)

    private fun idleEndFrom(now: Instant): Instant = minOf(now.plus(SessionPolicy.idleLifetime(kind)), absoluteExpiresOn)

    private fun copy(
        lastUsedOn: Instant = this.lastUsedOn,
        idleExpiresOn: Instant = this.idleExpiresOn,
        revokedOn: Instant? = this.revokedOn,
        revokeReason: RevokeReason? = this.revokeReason,
    ) = AuthSession(id, userId, kind, createdOn, lastUsedOn, idleExpiresOn, absoluteExpiresOn, revokedOn, revokeReason, device)

    /** What a refresh token sent back leads to. */
    sealed interface Refresh {
        /** Revoked or out of time: log in again. */
        data object Refused : Refresh

        /** The current token: rotate it, and save [session] (used now, idle end moved). */
        data class Rotate(val session: AuthSession) : Refresh

        /** Rotated a moment ago by another tab: an access token only. */
        data object WithinGrace : Refresh

        /** Rotated [sinceRotation] ago: copied. Save [session], which is revoked. */
        data class Reused(val session: AuthSession, val sinceRotation: Duration) : Refresh
    }

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
