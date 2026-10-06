package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** The one or two sentences under the product name. Optional. */
@JvmInline
value class ShortDescription private constructor(val value: String) {

    // Second wall: an empty description is stored as no description, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid ShortDescription '$value'" }
    }

    companion object {
        /** From the contract's ProductUpsertRequest. */
        const val MAX_LENGTH = 300

        /** Optional text: missing or blank means "none" (null), not an error. Spaces around it are trimmed. */
        fun makeNew(raw: String?): Result<ShortDescription?, Failure> {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed.isEmpty() -> Result.success(null)
                trimmed.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(ShortDescription(trimmed))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): ShortDescription = ShortDescription(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "shortDescription", meta = meta) {
        data object TooLong : Failure("SHORT_DESCRIPTION_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
