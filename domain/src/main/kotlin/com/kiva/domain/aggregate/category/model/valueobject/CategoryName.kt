package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The name of a category as shoppers see it (کیف دوشی). Spaces around it are trimmed; the text itself
 * is kept exactly as typed, Persian digits included, because it is shown as it is.
 */
@JvmInline
value class CategoryName private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid CategoryName '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 100

        fun makeNew(raw: String): Result<CategoryName, Failure> {
            val trimmed = raw.trim()
            return when {
                trimmed.isEmpty() -> Result.failure(Failure.Blank)
                trimmed.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(CategoryName(trimmed))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): CategoryName = CategoryName(value)
    }

    // Always the `name` field of the contract's CategoryUpsertRequest.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "name", meta = meta) {
        data object Blank : Failure("CATEGORY_NAME_REQUIRED")
        data object TooLong : Failure("CATEGORY_NAME_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
