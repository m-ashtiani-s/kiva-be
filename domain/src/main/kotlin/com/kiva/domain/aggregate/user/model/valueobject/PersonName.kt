package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersian

/**
 * A first or last name, as the shop greets the customer («سلام سارا»).
 *
 * Kept normalized ([normalizePersian]): an Arabic keyboard's «علي» is stored as «علی», so search and the admin's
 * customer list find it either way. Control characters and `<` `>` are refused: a name is shown in the panel, in
 * SMS and in emails, and has no use for them.
 */
@JvmInline
value class PersonName private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(::isForbidden)) { "Invalid PersonName" }
    }

    companion object {
        /** From the contract's UpdateProfileRequest. */
        const val MAX_LENGTH = 50

        /**
         * An empty name is refused, not taken as "no name": to clear a name the client sends null. So a form that
         * sends "" by mistake never wipes a name.
         */
        fun makeNew(raw: String): Result<PersonName, Failure> {
            val cleaned = raw.normalizePersian()
            return when {
                cleaned.isEmpty() -> Result.failure(Failure.Blank)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(::isForbidden) -> Result.failure(Failure.InvalidCharacters)
                else -> Result.success(PersonName(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): PersonName = PersonName(value)

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '<' || char == '>'
    }

    // No field here: the caller names it (`firstName`, `lastName`).
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object Blank : Failure("PERSON_NAME_REQUIRED")
        data object TooLong : Failure("PERSON_NAME_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("PERSON_NAME_INVALID")
    }
}
