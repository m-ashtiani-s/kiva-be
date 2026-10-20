package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits

/**
 * The shop's support number, as the contact page and the footer show it: an Iranian mobile (`09121234567`) or landline
 * with its area code (`02191001234`), 11 digits from 0. What the panel types has its digits made Latin and its spaces
 * and dashes dropped («۰۲۱-۹۱۰۰ ۱۲۳۴» is `02191001234`). The shop's own public number, not a person's.
 */
@JvmInline
value class SupportPhone private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid SupportPhone '$value'" }
    }

    companion object {
        private val FORMAT = Regex("0[1-9][0-9]{9}")

        fun makeNew(raw: String): Result<SupportPhone, Failure> {
            val digits = raw.normalizeDigits().filterNot { it.isWhitespace() || it == '-' }
            return if (FORMAT.matches(digits)) Result.success(SupportPhone(digits)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): SupportPhone = SupportPhone(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "supportPhone") {
        data object Invalid : Failure("SUPPORT_PHONE_INVALID")
    }
}
