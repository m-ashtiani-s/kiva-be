package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersian

/**
 * Who receives the parcel, full name («سارا محمدی»). The rules of a person's name (normalized, no control characters,
 * no `<` `>`), but longer, because it is a first and a last name together.
 *
 * Personal data: [toString] never shows it.
 */
@JvmInline
value class RecipientName private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly. The name itself stays out of the message.
    init {
        require(value.length in MIN_LENGTH..MAX_LENGTH && value.none(::isForbidden)) { "Invalid RecipientName of length ${value.length}" }
    }

    override fun toString(): String = "RecipientName(***)"

    companion object {
        /** From the contract's AddressInput. */
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 80

        fun makeNew(raw: String): Result<RecipientName, Failure> {
            val cleaned = raw.normalizePersian()
            return when {
                cleaned.isEmpty() -> Result.failure(Failure.Blank)
                cleaned.length < MIN_LENGTH -> Result.failure(Failure.TooShort)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(::isForbidden) -> Result.failure(Failure.InvalidCharacters)
                else -> Result.success(RecipientName(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): RecipientName = RecipientName(value)

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '<' || char == '>'
    }

    // Always the `recipientName` field of the contract's AddressInput.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "recipientName", meta = meta) {
        data object Blank : Failure("RECIPIENT_NAME_REQUIRED")
        data object TooShort : Failure("RECIPIENT_NAME_TOO_SHORT", mapOf("minLength" to MIN_LENGTH))
        data object TooLong : Failure("RECIPIENT_NAME_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("RECIPIENT_NAME_INVALID")
    }
}
