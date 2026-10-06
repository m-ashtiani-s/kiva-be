package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The product's own words about its material, more precise than the material it belongs to:
 * the material is «ساتن», this is «ساتن با سگک فلزی طلایی». Optional.
 */
@JvmInline
value class MaterialDescription private constructor(val value: String) {

    // Second wall: an empty description is stored as no description, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid MaterialDescription '$value'" }
    }

    companion object {
        /** From the contract's ProductUpsertRequest. */
        const val MAX_LENGTH = 120

        /** Optional text: missing or blank means "none" (null), not an error. Spaces around it are trimmed. */
        fun makeNew(raw: String?): Result<MaterialDescription?, Failure> {
            val trimmed = raw?.trim().orEmpty()
            return when {
                trimmed.isEmpty() -> Result.success(null)
                trimmed.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(MaterialDescription(trimmed))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): MaterialDescription = MaterialDescription(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "materialDescription", meta = meta) {
        data object TooLong : Failure("MATERIAL_DESCRIPTION_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
