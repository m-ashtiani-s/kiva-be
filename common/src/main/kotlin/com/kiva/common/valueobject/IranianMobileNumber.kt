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
 */
@JvmInline
value class IranianMobileNumber private constructor(val value: String) {

    companion object {
        fun of(raw: String): Result<IranianMobileNumber, Failure> {
            val cleaned = raw.normalizeDigits().filterNot { it in IGNORED }
            if (cleaned.isEmpty()) return Result.failure(Failure.Blank)

            val national = cleaned
                .removeCountryCode()
                .removePrefix("0")

            val valid = national.length == 10 && national[0] == '9' && national.all { it in '0'..'9' }
            return if (valid) Result.success(IranianMobileNumber("0$national")) else Result.failure(Failure.Invalid)
        }

        private const val IGNORED = " -()\t"

        private fun String.removeCountryCode(): String = when {
            startsWith("+98") -> drop(3)
            startsWith("0098") -> drop(4)
            // "98" only counts as a country code when the rest is a full number, so "9812..." isn't cut.
            startsWith("98") && length >= 12 -> drop(2)
            else -> this
        }
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "mobile") {
        data object Blank : Failure("MOBILE_BLANK")
        data object Invalid : Failure("MOBILE_INVALID")
    }
}
