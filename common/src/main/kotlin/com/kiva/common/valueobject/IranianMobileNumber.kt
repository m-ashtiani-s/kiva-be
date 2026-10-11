package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits

/**
 * An Iranian mobile number, always stored in one form: `09121234567` (11 digits).
 *
 * Accepted input, all meaning the same number:
 * `09121234567`, `9121234567`, `+989121234567`, `00989121234567`, `989121234567`, `+98 0912 123 4567`,
 * `۰۹۱۲-۱۲۳-۴۵۶۷` (Persian digits, spaces, dashes and parentheses are fine).
 *
 * Because there is only one stored form, the same person can't end up with two accounts
 * just because they typed their number differently the second time.
 *
 * A number is personal data: write [masked] to logs, never [value].
 */
@JvmInline
value class IranianMobileNumber private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly. The number itself stays out of the message.
    init {
        require(value.length == 11 && value.startsWith("09") && value.all { it in '0'..'9' }) {
            "Invalid IranianMobileNumber of length ${value.length}"
        }
    }

    /** The number for logs: `0912***4567`. Enough to tell two numbers apart in a log, not enough to call anyone. */
    fun masked(): String = value.take(4) + "***" + value.takeLast(4)

    companion object {
        fun makeNew(raw: String): Result<IranianMobileNumber, Failure> {
            val cleaned = raw.normalizeDigits().filterNot { it in IGNORED }
            if (cleaned.isEmpty()) return Result.failure(Failure.Blank)

            val national = cleaned
                .removeCountryCode()
                .removePrefix("0")

            val valid = national.length == 10 && national[0] == '9' && national.all { it in '0'..'9' }
            return if (valid) Result.success(IranianMobileNumber("0$national")) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage, always in the stored form (`09121234567`). Still checked by `init`. */
        fun constructFromPersisted(value: String): IranianMobileNumber = IranianMobileNumber(value)

        private const val IGNORED = " -()\t"

        private fun String.removeCountryCode(): String = when {
            startsWith("+98") -> drop(3)
            startsWith("0098") -> drop(4)
            // "98" only counts as a country code when the rest is a full number, so "9812..." isn't cut.
            startsWith("98") && length >= 12 -> drop(2)
            else -> this
        }
    }

    // Codes and field name follow the API contract (`phone`, PHONE_INVALID).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "phone") {
        data object Blank : Failure("PHONE_REQUIRED")
        data object Invalid : Failure("PHONE_INVALID")
    }
}
