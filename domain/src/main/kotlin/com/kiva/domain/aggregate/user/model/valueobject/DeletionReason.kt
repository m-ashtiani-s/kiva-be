package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * Why a customer deletes their account (`DELETE /me`'s optional `reason`, roadmap phase 14, decision 11). Kept without
 * anything that leads back to the person: only the text and the day. It may say anything, so [toString] hides it.
 */
@JvmInline
value class DeletionReason private constructor(val value: String) {

    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(Char::isISOControl)) { "Invalid DeletionReason of length ${value.length}" }
    }

    override fun toString(): String = "DeletionReason(***)"

    companion object {
        /** From the contract's `reason`; accountdeletionreason_tbl's column has the same size. */
        const val MAX_LENGTH = 300

        /** Missing or blank means no reason (null), not an error. Letters and spaces normalized, line breaks become spaces. */
        fun makeNew(raw: String?): Result<DeletionReason?, Failure> {
            val cleaned = raw?.normalizePersianLetters().orEmpty()
            return when {
                cleaned.isEmpty() -> Result.success(null)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(Char::isISOControl) -> Result.failure(Failure.Invalid)
                else -> Result.success(DeletionReason(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): DeletionReason = DeletionReason(value)
    }

    // Always the `reason` field of DELETE /me.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field = "reason", meta = meta) {
        data object TooLong : Failure("DELETION_REASON_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object Invalid : Failure("DELETION_REASON_INVALID")
    }
}
