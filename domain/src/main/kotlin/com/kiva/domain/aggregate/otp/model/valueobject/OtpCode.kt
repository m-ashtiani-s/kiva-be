package com.kiva.domain.aggregate.otp.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy

/**
 * A one-time code: exactly 5 digits, leading zeros included (`00042`).
 *
 * A secret: it goes into the SMS and nowhere else. It is never stored (only its hash) and never logged, so [toString]
 * hides it; a `logger.info("$code")` by mistake writes stars. Read [value] only to send the SMS or to hash it.
 */
@JvmInline
value class OtpCode private constructor(val value: String) {

    // Second wall, for every way in: makeNew and of already make only valid codes, so this only ever fails on a bug.
    // The code itself stays out of the message.
    init {
        require(value.length == OtpPolicy.CODE_LENGTH && value.all { it in '0'..'9' }) { "Invalid OtpCode of length ${value.length}" }
    }

    override fun toString(): String = "OtpCode(*****)"

    companion object {
        /** A random number from 0 to 99,999 (the caller's secure random) written with 5 digits: 42 is `00042`. */
        fun of(number: Int): OtpCode {
            require(number in 0..<UPPER_BOUND) { "An OtpCode number is from 0 to ${UPPER_BOUND - 1}" }
            return OtpCode(number.toString().padStart(OtpPolicy.CODE_LENGTH, '0'))
        }

        /** How many codes there are, so `nextInt(UPPER_BOUND)` picks any of them. */
        const val UPPER_BOUND = 100_000

        /**
         * A code as the user typed it. Persian or Arabic digits are fine (۴۸۲۱۳), and so are spaces around it.
         * Anything but exactly 5 digits is refused here, before it is ever compared: a mistyped format is not a wrong
         * guess and takes none of the code's tries.
         */
        fun makeNew(raw: String): Result<OtpCode, Failure> {
            val cleaned = raw.normalizeDigits().trim()
            val valid = cleaned.length == OtpPolicy.CODE_LENGTH && cleaned.all { it in '0'..'9' }
            return if (valid) Result.success(OtpCode(cleaned)) else Result.failure(Failure.InvalidFormat)
        }
    }

    // No field here: the caller names it (`code`, or `currentPhoneCode` and `newPhoneCode` in a phone change).
    sealed class Failure(code: String, meta: Map<String, Any>) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object InvalidFormat : Failure("OTP_CODE_FORMAT_INVALID", mapOf("codeLength" to OtpPolicy.CODE_LENGTH))
    }
}
