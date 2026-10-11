package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersian

/** The flat inside the building («واحد ۴»), for the courier. Optional; stored like [Plaque], digits in English. */
@JvmInline
value class UnitNumber private constructor(val value: String) {

    // Second wall: an empty unit is stored as none, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(::isForbidden)) { "Invalid UnitNumber '$value'" }
    }

    companion object {
        /** From the contract's AddressInput. */
        const val MAX_LENGTH = 10

        /** Optional text: missing or blank means "none" (null), not an error. */
        fun makeNew(raw: String?): Result<UnitNumber?, Failure> {
            val cleaned = raw?.normalizePersian().orEmpty()
            return when {
                cleaned.isEmpty() -> Result.success(null)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(::isForbidden) -> Result.failure(Failure.InvalidCharacters)
                else -> Result.success(UnitNumber(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): UnitNumber = UnitNumber(value)

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '<' || char == '>'
    }

    // Always the `unit` field of the contract's AddressInput.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "unit", meta = meta) {
        data object TooLong : Failure("UNIT_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("UNIT_INVALID")
    }
}
