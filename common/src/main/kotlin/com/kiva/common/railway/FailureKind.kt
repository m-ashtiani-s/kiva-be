package com.kiva.common.railway

/**
 * What kind of problem a failure is, in business terms.
 * The HTTP layer turns this into a status code (INVALID -> 400, NOT_FOUND -> 404, ...);
 * common itself knows nothing about HTTP.
 */
enum class FailureKind {
    /** The input breaks a rule: blank, wrong format, out of range. */
    INVALID,

    /** The thing asked for does not exist (or the caller may not know it exists). */
    NOT_FOUND,

    /** The request is valid but clashes with the current state: duplicate, out of stock, already paid. */
    CONFLICT,

    /** The caller is not logged in. */
    UNAUTHORIZED,

    /** The caller is logged in but not allowed to do this. */
    FORBIDDEN,
}
