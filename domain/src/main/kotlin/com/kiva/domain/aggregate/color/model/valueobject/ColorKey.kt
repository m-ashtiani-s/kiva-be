package com.kiva.domain.aggregate.color.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The stable key of a palette color (`lilac`, `black`). It is the color's identity: products, filters and
 * category icons refer to a color by this key, so it never changes once in use.
 *
 * Format from the API contract: starts with a lowercase letter, then lowercase letters, digits or dashes,
 * 2 to 31 characters in total.
 */
@JvmInline
value class ColorKey private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong (a bad row or, later, a bad stored event). That is a bug: stop loudly.
    init {
        require(FORMAT.matches(value)) { "Invalid ColorKey '$value'" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("[a-z][a-z0-9-]{1,30}")

        fun makeNew(raw: String): Result<ColorKey, Failure> =
            if (FORMAT.matches(raw)) Result.success(ColorKey(raw)) else Result.failure(Failure.Invalid)

        /**
         * For values read back from storage (a table row, a stored event). Still checked by `init`:
         * a broken stored value throws instead of spreading through the system.
         */
        fun constructFromPersisted(value: String): ColorKey = ColorKey(value)
    }

    // No field here: the same key arrives as `key`, `colorKey` or the `color` filter; the caller names it.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("COLOR_KEY_INVALID")
    }
}
