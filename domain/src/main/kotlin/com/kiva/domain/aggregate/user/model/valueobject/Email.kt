package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * An email address the customer typed into their profile. Kept trimmed and in lower case (`Sara@X.com` is
 * `sara@x.com`), so the same address is never two different values.
 *
 * Never verified: nobody has proved they own it. So it is only for contacting the customer, and never a way to log
 * in or to get an account back. Login is the phone alone.
 */
@JvmInline
value class Email private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly. The address itself stays out of the message.
    init {
        require(value.length <= MAX_LENGTH && PATTERN.matches(value)) { "Invalid Email of length ${value.length}" }
    }

    companion object {
        /** The longest address the email standard (RFC 5321) allows. */
        const val MAX_LENGTH = 254

        // A simple shape, not the full standard: something@domain.tld, in the characters people actually use.
        private val PATTERN = Regex("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}")

        /** An empty address is refused, not taken as "no email": to clear it the client sends null. */
        fun makeNew(raw: String): Result<Email, Failure> {
            val cleaned = raw.trim().lowercase()
            return when {
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                !PATTERN.matches(cleaned) -> Result.failure(Failure.Invalid)
                else -> Result.success(Email(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): Email = Email(value)
    }

    // Always the `email` field of the contract.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "email", meta = meta) {
        data object Invalid : Failure("EMAIL_INVALID")
        data object TooLong : Failure("EMAIL_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
