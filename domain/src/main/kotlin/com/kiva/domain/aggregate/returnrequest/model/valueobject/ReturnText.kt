package com.kiva.domain.aggregate.returnrequest.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.domain.aggregate.order.model.valueobject.OptionalText

/**
 * A free text of a return: the customer's description, staff's instructions, the reason it was turned down or closed,
 * the pickup's note, the receipt's note (roadmap section 12.2, decision 21). The order's one rule of free texts
 * (OptionalText): shown back as written, line breaks become spaces, control characters refused (TEXT_INVALID); each field
 * has its own longest length (TEXT_TOO_LONG with `meta.maxLength`). No field here: the caller names it (`forField`).
 *
 * Personal or internal: never logged ([toString]).
 */
@JvmInline
value class ReturnText private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(OptionalText.isValid(value, MAX_LENGTH)) { "Invalid ReturnText of length ${value.length}" }
    }

    override fun toString(): String = "ReturnText(***)"

    companion object {
        /** The longest of them all: the description, the instructions and the receipt's note. */
        const val MAX_LENGTH = 1000

        /** A reason to turn a return down or close it (decisions 8 and 18). */
        const val MAX_REASON_LENGTH = 500

        /** The pickup's note (decision 9). */
        const val MAX_NOTE_LENGTH = 500

        /** A reason is at least this long: «نه» is not a reason. */
        const val MIN_REASON_LENGTH = 3

        /** An optional text of at most [maxLength]: missing or blank is none (null). */
        fun makeNew(raw: String?, maxLength: Int = MAX_LENGTH): Result<ReturnText?, ResultFailure> {
            require(maxLength in 1..MAX_LENGTH) { "A return's text is at most $MAX_LENGTH long, not $maxLength" }
            return when (val checked = OptionalText.check(raw, maxLength)) {
                is OptionalText.Checked.Fine -> Result.success(checked.value?.let(::ReturnText))
                OptionalText.Checked.TooLong -> Result.failure(TextTooLong(maxLength))
                OptionalText.Checked.InvalidCharacters -> Result.failure(TextInvalid)
            }
        }

        /**
         * A reason staff must give, [MIN_REASON_LENGTH] to [MAX_REASON_LENGTH] long: missing or shorter is [missing]
         * (REJECTION_REASON_REQUIRED, CLOSE_REASON_REQUIRED).
         */
        fun makeReason(raw: String?, missing: Failure): Result<ReturnText, ResultFailure> = when (val made = makeNew(raw, MAX_REASON_LENGTH)) {
            is Result.Failure -> made
            is Result.Success -> made.value?.takeIf { it.value.length >= MIN_REASON_LENGTH }?.let { Result.success(it) } ?: Result.failure(missing)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): ReturnText = ReturnText(value)
    }

    /** A reason that must be given; no field here, as for the shared failures. */
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object RejectionReasonRequired : Failure("REJECTION_REASON_REQUIRED")
        data object CloseReasonRequired : Failure("CLOSE_REASON_REQUIRED")
    }
}
