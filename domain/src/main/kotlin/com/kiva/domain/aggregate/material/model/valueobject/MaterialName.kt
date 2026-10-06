package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The name of a material as shoppers see it (چرم طبیعی گاوی). Spaces around it are trimmed; the text itself
 * is kept exactly as typed, Persian digits included, because it is shown as it is.
 */
@JvmInline
value class MaterialName private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid MaterialName '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 120

        fun makeNew(raw: String): Result<MaterialName, Failure> {
            val trimmed = raw.trim()
            return when {
                trimmed.isEmpty() -> Result.failure(Failure.Blank)
                trimmed.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(MaterialName(trimmed))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): MaterialName = MaterialName(value)
    }

    // Always the `name` field of the contract's MaterialUpsertRequest.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "name", meta = meta) {
        data object Blank : Failure("MATERIAL_NAME_REQUIRED")
        data object TooLong : Failure("MATERIAL_NAME_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
