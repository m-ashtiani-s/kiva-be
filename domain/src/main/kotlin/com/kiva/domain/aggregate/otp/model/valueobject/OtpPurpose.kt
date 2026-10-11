package com.kiva.domain.aggregate.otp.model.valueobject

/**
 * What a code was sent for. It is part of the code's hash, so a code sent for one purpose is never accepted for
 * another: a login code can't confirm a phone change.
 *
 * No `makeNew`: the purpose is never taken from a request. Each endpoint knows its own (the contract's send endpoint
 * takes only LOGIN).
 */
enum class OtpPurpose {
    /** Logging in (or signing up: the same thing). Sent to the typed phone; no user is known yet. */
    LOGIN,

    /** Changing the phone: the code sent to the account's current phone. */
    PHONE_CHANGE_CURRENT,

    /** Changing the phone: the code sent to the new phone. */
    PHONE_CHANGE_NEW,

    /** Deleting the account. Comes after the orders phase. */
    DELETE_ACCOUNT,
    ;

    /** Every purpose but LOGIN is asked for by a logged-in user. */
    val forUser: Boolean get() = this != LOGIN
}
