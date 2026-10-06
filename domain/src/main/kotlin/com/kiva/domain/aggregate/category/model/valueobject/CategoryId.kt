package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The numeric id of a category (`categoryId` in the API). Products refer to their category by it.
 * Ids come from the database and start at 1, so zero or negative is never an id.
 *
 * A valid id is not proof the category exists; that needs a lookup, which is the caller's job.
 */
@JvmInline
value class CategoryId private constructor(val value: Long) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong (a bad row or, later, a bad stored event). That is a bug: stop loudly.
    init {
        require(value > 0) { "Invalid CategoryId $value" }
    }

    companion object {
        fun makeNew(raw: Long): Result<CategoryId, Failure> =
            if (raw > 0) Result.success(CategoryId(raw)) else Result.failure(Failure.Invalid)

        /**
         * For values read back from storage (a table row, a stored event). Still checked by `init`:
         * a broken stored value throws instead of spreading through the system.
         */
        fun constructFromPersisted(value: Long): CategoryId = CategoryId(value)
    }

    // No field here: the caller names it (`categoryId`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("CATEGORY_ID_INVALID")
    }
}
