package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * A material's own description (the material table's `description`): what the material is, for its spec table.
 * Named so to keep it apart from a product's MaterialDescription, the product's own wording of its material.
 * Trimmed and shown as typed; empty means none. Not in the products' search text: long text crowds the results.
 */
@JvmInline
value class MaterialNote private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid MaterialNote" }
    }

    companion object {
        const val MAX_LENGTH = 1000

        /** Null or blank is no description at all. */
        fun makeNew(raw: String?): Result<MaterialNote?, Failure> {
            val text = raw?.normalizePersianLetters().orEmpty()
            return when {
                text.isEmpty() -> Result.success(null)
                text.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(MaterialNote(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): MaterialNote = MaterialNote(value)
    }

    // Always the `description` field of the contract's MaterialUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "description", meta = mapOf("maxLength" to MAX_LENGTH)) {
        data object TooLong : Failure("MATERIAL_NOTE_TOO_LONG")
    }
}
