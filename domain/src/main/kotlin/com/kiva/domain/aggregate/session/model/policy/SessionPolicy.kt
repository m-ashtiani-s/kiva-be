package com.kiva.domain.aggregate.session.model.policy

import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import java.time.Duration

/**
 * How long logins last, from the contract. A staff session is much shorter: a stolen panel session can refund money
 * and change roles.
 */
object SessionPolicy {

    /** An access token works this long; the client then refreshes it silently. Short enough not to need revoking one by one. */
    val ACCESS_TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)

    /**
     * A rotated refresh token is still taken this long after its rotation: two tabs refreshing at the same moment both
     * get an answer. Used later than that, it means the token was copied, and the session is revoked.
     */
    val ROTATION_GRACE: Duration = Duration.ofSeconds(30)

    /** A session not refreshed for this long ends. */
    fun idleLifetime(kind: SessionKind): Duration = when (kind) {
        SessionKind.CUSTOMER -> Duration.ofDays(30)
        SessionKind.PANEL -> Duration.ofHours(12)
    }

    /** A session ends this long after the login, however often it is refreshed. */
    fun absoluteLifetime(kind: SessionKind): Duration = when (kind) {
        SessionKind.CUSTOMER -> Duration.ofDays(180)
        SessionKind.PANEL -> Duration.ofDays(7)
    }
}
