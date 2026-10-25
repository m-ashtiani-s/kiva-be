package com.kiva.application.port

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import java.time.Instant

/**
 * The append-only log of what matters for security: logins, wrong codes, token theft, role changes. Kept apart from
 * the users' rows, so it stays when an account is deleted and nothing can rewrite it.
 */
interface SecurityEventLog {

    /**
     * Adds [event], in the caller's transaction. When the answer to the user is a failure, the use case must still
     * commit (return a successful outcome from the transaction), or the event is rolled back with everything else.
     */
    fun record(event: SecurityEvent)
}

/**
 * @property detail a few plain values about it (numbers, strings, booleans), never a code, a token or a whole phone.
 */
data class SecurityEvent(
    val type: SecurityEventType,
    val occurredOn: Instant,
    val userId: UserId? = null,
    val phone: IranianMobileNumber? = null,
    val sessionId: SessionId? = null,
    val ip: String? = null,
    val detail: Map<String, Any> = emptyMap(),
)

enum class SecurityEventType {
    /** The first login of a phone made its account. */
    USER_REGISTERED,
    LOGIN_SUCCEEDED,
    OTP_VERIFY_FAILED,

    /** The phone reached the cap of wrong codes in a day. */
    OTP_LOCKED,

    /** A rotated refresh token was used again: the session is revoked as stolen. */
    REFRESH_REUSE_DETECTED,
    LOGOUT,
    LOGOUT_ALL,
    PHONE_CHANGED,
    ROLES_CHANGED,

    /** The first admin was made from the environment. */
    ADMIN_BOOTSTRAPPED,
    PANEL_ACCESS_DENIED,

    /** The shop sent its cap of SMS in the last hour; no code goes out until the hour moves. */
    SMS_HOURLY_CAP_REACHED,

    /** The user deleted their account (`DELETE /me`); `detail.ordersErased` is how many of its orders were made anonymous. */
    ACCOUNT_DELETED,
}
