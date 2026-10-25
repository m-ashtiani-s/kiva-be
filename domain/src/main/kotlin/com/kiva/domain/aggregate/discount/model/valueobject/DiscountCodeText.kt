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

        /** A code the panel makes: at least 3 characters (the contract), where the shop still reads the older 2-letter ones. */
        private val PANEL_FORMAT = Regex("[A-Z0-9_-]{3,32}")

        /**
         * A code as the panel typed it (roadmap phase 4, decision 9), normalised the way a shopper's is: ` paeez۱۵ ` is
         * PAEEZ15. Blank is FIELD_REQUIRED; anything [PANEL_FORMAT] refuses is DISCOUNT_CODE_TEXT_INVALID, both on `code`.
         * [legacyAllowed] for an edit: the older 2-letter codes stay valid, and the use case refuses one that isn't the code's
         * own text ([isLegacy]).
         */
        fun makeNewInPanel(raw: String?, legacyAllowed: Boolean = false): Result<DiscountCodeText, PanelFailure> {
            val text = raw?.normalizeDigits()?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return when {
                text.isEmpty() -> Result.failure(PanelFailure.Required)
                PANEL_FORMAT.matches(text) -> Result.success(DiscountCodeText(text))
                legacyAllowed && FORMAT.matches(text) -> Result.success(DiscountCodeText(text))
                else -> Result.failure(PanelFailure.Invalid)
            }
        }
    }

    /** One of the older 2-letter codes, which the shop still reads but the panel no longer makes. */
    val isLegacy: Boolean get() = value.length < 3

    sealed class PanelFailure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "code") {
        data object Required : PanelFailure("FIELD_REQUIRED")

        data object Invalid : PanelFailure("DISCOUNT_CODE_TEXT_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null) : ResultFailure(code, kind, field = field) {
        data object Required : Failure("DISCOUNT_CODE_REQUIRED", FailureKind.INVALID, field = "code")

        data object Invalid : Failure("DISCOUNT_CODE_INVALID", FailureKind.BUSINESS_RULE)
    }
}
