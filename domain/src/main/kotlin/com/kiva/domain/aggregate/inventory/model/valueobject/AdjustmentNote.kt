package com.kiva.domain.aggregate.inventory.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** What staff wrote about a stock change («بارنامه ۲۳۴»، «دسته‌ش پاره شده»). Optional. */
@JvmInline
value class AdjustmentNote private constructor(val value: String) {

    // Second wall: an empty note is stored as no note, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid AdjustmentNote '$value'" }
    }

    companion object {
        /** From the contract's InventoryAdjustmentRequest. */
        const val MAX_LENGTH = 300

        /** Optional text: missing or blank means "none" (null), not an error. Spaces around it are trimmed. */
        fun makeNew(raw: String?): Result<AdjustmentNote?, Failure> {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed.isEmpty() -> Result.success(null)
                trimmed.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(AdjustmentNote(trimmed))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): AdjustmentNote = AdjustmentNote(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "note", meta = meta) {
        data object TooLong : Failure("ADJUSTMENT_NOTE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
