package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersian

/**
 * The building's number («پلاک ۱۲»), for the courier. Optional. A short code rather than prose, so its digits are
 * stored in English like the postal code (`12`, `12/1`); the address's full text shows them in Persian.
 */
@JvmInline
value class Plaque private constructor(val value: String) {

    // Second wall: an empty plaque is stored as none, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(::isForbidden)) { "Invalid Plaque '$value'" }
    }

    companion object {
        /** From the contract's AddressInput. */
        const val MAX_LENGTH = 10

        /** Optional text: missing or blank means "none" (null), not an error. */
        fun makeNew(raw: String?): Result<Plaque?, Failure> {
            val cleaned = raw?.normalizePersian().orEmpty()
            return when {
                cleaned.isEmpty() -> Result.success(null)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(::isForbidden) -> Result.failure(Failure.InvalidCharacters)
                else -> Result.success(Plaque(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): Plaque = Plaque(value)

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '<' || char == '>'
    }

    // Always the `plaque` field of the contract's AddressInput.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "plaque", meta = meta) {
        data object TooLong : Failure("PLAQUE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("PLAQUE_INVALID")
    }
}
