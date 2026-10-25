package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * The line under a category's name on its page (کیف‌های دوشی مینیمال برای استفاده‌ی روزمره). Trimmed and shown as typed
 * (Persian letters normalized); empty means none.
 */
@JvmInline
value class CategoryDescription private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid CategoryDescription" }
    }

    companion object {
        const val MAX_LENGTH = 500

        /** Null or blank is no description at all. */
        fun makeNew(raw: String?): Result<CategoryDescription?, Failure> {
            val text = raw?.normalizePersianLetters().orEmpty()
            return when {
                text.isEmpty() -> Result.success(null)
                text.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(CategoryDescription(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): CategoryDescription = CategoryDescription(value)
    }

    // Always the `description` field of the contract's CategoryUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "description", meta = mapOf("maxLength" to MAX_LENGTH)) {
        data object TooLong : Failure("CATEGORY_DESCRIPTION_TOO_LONG")
    }
}
