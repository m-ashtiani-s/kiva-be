package com.kiva.domain.aggregate.session.model.valueobject

/** Why a session ended before its time. */
enum class RevokeReason {
    /** Its owner logged out on this device. */
    LOGOUT,

    /** Its owner logged out everywhere. */
    LOGOUT_ALL,

    /** A rotated refresh token of it was used again after the grace: it was copied. */
    REUSE_DETECTED,

    /** The account moved to another phone. */
    PHONE_CHANGED,

    /** The user's roles changed, so the next login gets the right kind of session. */
    ROLES_CHANGED,

    /** The account was deleted. */
    ACCOUNT_DELETED,
}
