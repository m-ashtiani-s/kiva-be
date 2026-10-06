package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The readable name of a page in its URL: `shoulder` in `/categories/shoulder`, `mahak-shoulder-bag` in
 * `/products/mahak-shoulder-bag`. Used by categories, products, materials and (later) blog posts.
 *
 * Rules (from the API contract): lowercase Latin letters, digits and single dashes between them, and
 * **at least one letter**. The letter is what lets `GET /products/{idOrSlug}` tell a slug from a numeric id:
 * `12` is always an id, never a slug.
 *
 * Input is taken as it is, not lowercased or trimmed: a slug is an address, and `Shoulder` silently
 * becoming `shoulder` would hide a wrong link instead of reporting it.
 */
@JvmInline
value class Slug private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong (a bad row or, later, a bad stored event). That is a bug: stop loudly.
    init {
        require(isValid(value)) { "Invalid Slug '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 100

        // Kotlin's matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("(?=.*[a-z])[a-z0-9]+(-[a-z0-9]+)*")

        private fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && FORMAT.matches(value)

        fun makeNew(raw: String): Result<Slug, Failure> =
            if (isValid(raw)) Result.success(Slug(raw)) else Result.failure(Failure.Invalid)

        /**
         * For values read back from storage (a table row, a stored event). Still checked by `init`:
         * a broken stored value throws instead of spreading through the system.
         */
        fun constructFromPersisted(value: String): Slug = Slug(value)
    }

    // No field here: the caller knows which input it was (`slug`, `category`, ...) and names it with forField.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("SLUG_INVALID")
    }
}
