package com.kiva.domain.aggregate.discount.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import java.util.Locale

/**
 * The text of a discount code (`KIVA10`), as the shop keeps it: upper case Latin letters, digits, `-` and `_`, 2 to 32
 * of them. What the shopper types is trimmed, its digits made Latin and its letters upper case, so «kiva10 » and
 * «KIVA۱۰» are both KIVA10 (roadmap section 14; the contract's «حساس به حروف نیست»).
 */
@JvmInline
value class DiscountCodeText private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input, so this only fails when stored data is wrong.
    init {
        require(FORMAT.matches(value)) { "Invalid DiscountCodeText '$value'" }
    }

    companion object {
        private val FORMAT = Regex("[A-Z0-9_-]{2,32}")

        /**
         * A code as the shopper typed it. Nothing typed is the request's shape (400 on `code`); anything else no code can
         * be (too short or long, another alphabet) is the same answer as a code that doesn't exist, DISCOUNT_CODE_INVALID:
         * the shopper only learns the code doesn't work.
         */
        fun makeNew(raw: String): Result<DiscountCodeText, Failure> {
            val text = raw.normalizeDigits().trim().uppercase(Locale.ROOT)
            return when {
                text.isEmpty() -> Result.failure(Failure.Required)
                !FORMAT.matches(text) -> Result.failure(Failure.Invalid)
                else -> Result.success(DiscountCodeText(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): DiscountCodeText = DiscountCodeText(value)
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null) : ResultFailure(code, kind, field = field) {
        data object Required : Failure("DISCOUNT_CODE_REQUIRED", FailureKind.INVALID, field = "code")

        data object Invalid : Failure("DISCOUNT_CODE_INVALID", FailureKind.BUSINESS_RULE)
    }
}
