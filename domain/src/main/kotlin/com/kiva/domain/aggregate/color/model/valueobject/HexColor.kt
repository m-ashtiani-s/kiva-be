package com.kiva.domain.aggregate.color.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** The swatch color of a palette color, `#RRGGBB` (contract pattern `^#[0-9A-Fa-f]{6}$`). */
@JvmInline
value class HexColor private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(FORMAT.matches(value)) { "Invalid HexColor '$value'" }
    }

    companion object {
        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("#[0-9A-Fa-f]{6}")

        /** Trimmed and uppercased, so the same color is always written the same way (`#C8B6E2`, never `#c8b6e2`). */
        fun makeNew(raw: String): Result<HexColor, Failure> {
            val normalized = raw.trim().uppercase()
            return if (FORMAT.matches(normalized)) Result.success(HexColor(normalized)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): HexColor = HexColor(value)
    }

    // Always the `hex` field of the contract's ColorUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "hex") {
        data object Invalid : Failure("HEX_COLOR_INVALID")
    }
}
