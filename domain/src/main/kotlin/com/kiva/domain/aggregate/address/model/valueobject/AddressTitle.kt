package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/** The customer's own label for an address («خانه»، «محل کار»). Optional; shown as written, digits included. */
@JvmInline
value class AddressTitle private constructor(val value: String) {

    // Second wall: an empty title is stored as no title, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(::isForbidden)) { "Invalid AddressTitle '$value'" }
    }

    companion object {
        /** From the contract's AddressInput. */
        const val MAX_LENGTH = 30

        /** Optional text: missing or blank means "none" (null), not an error. */
        fun makeNew(raw: String?): Result<AddressTitle?, Failure> {
            val cleaned = raw?.normalizePersianLetters().orEmpty()
            return when {
                cleaned.isEmpty() -> Result.success(null)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(::isForbidden) -> Result.failure(Failure.InvalidCharacters)
                else -> Result.success(AddressTitle(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): AddressTitle = AddressTitle(value)

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '<' || char == '>'
    }

    // Always the `title` field of the contract's AddressInput.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "title", meta = meta) {
        data object TooLong : Failure("ADDRESS_TITLE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("ADDRESS_TITLE_INVALID")
    }
}
