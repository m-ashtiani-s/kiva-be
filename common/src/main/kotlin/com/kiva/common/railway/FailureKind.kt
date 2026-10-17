package com.kiva.common.railway

/**
 * What kind of problem a failure is, in business terms.
 * The HTTP layer turns this into a status code; common itself knows nothing about HTTP.
 *
 * The split between [INVALID] and [BUSINESS_RULE] follows the API contract:
 * INVALID = the input itself is malformed (400), BUSINESS_RULE = the input is fine but a rule forbids it (422).
 */
enum class FailureKind {
    /** The input breaks a format rule: blank, wrong pattern, out of range. -> 400 */
    INVALID,

    /** The caller is not logged in, or the token is no longer valid. -> 401 */
    UNAUTHORIZED,

    /** The caller is logged in but not allowed to do this. -> 403 */
    FORBIDDEN,

    /** The thing asked for does not exist (or the caller may not know it exists). -> 404 */
    NOT_FOUND,

    /** Clashes with existing data: duplicate slug, already subscribed, price changed meanwhile. -> 409 */
    CONFLICT,

    /** Valid input, but a business rule forbids it: out of stock, order not cancellable, code expired. -> 422 */
    BUSINESS_RULE,

    /** Too many attempts in a short time (OTP, tracking lookup). -> 429 */
    RATE_LIMITED,

    /** The version the caller edited (`If-Match`) is no longer the current one: someone saved in between. -> 412 */
    PRECONDITION_FAILED,

    /** An upload bigger than its limit. -> 413 */
    PAYLOAD_TOO_LARGE,

    /** An upload of a type that isn't accepted, judged by its content. -> 415 */
    UNSUPPORTED_MEDIA_TYPE,
}
