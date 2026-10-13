package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * The written part of an address: street, alley, building («خیابان ولیعصر، کوچه نسترن، پلاک ۱۲»).
 *
 * Letters and spaces are normalized ([normalizePersianLetters]), but the digits stay as typed: the customer reads this
 * text back in their address book, and «پلاک ۱۲» must not come back as «پلاک 12». Control characters and `<` `>` are
 * refused: it is printed on the parcel and shown in the panel.
 *
 * Personal data: [toString] never shows it, so it can't reach a log by accident.
 */
@JvmInline
value class AddressLine private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly. The text itself stays out of the message.
    init {
        require(value.length in MIN_LENGTH..MAX_LENGTH && value.none(::isForbidden)) { "Invalid AddressLine of length ${value.length}" }
    }

    override fun toString(): String = "AddressLine(***)"

    companion object {
        /** From the contract's AddressInput. */
        const val MIN_LENGTH = 10
        const val MAX_LENGTH = 300

        fun makeNew(raw: String): Result<AddressLine, Failure> {
            val cleaned = raw.normalizePersianLetters()
            return when {
                cleaned.length < MIN_LENGTH -> Result.failure(Failure.TooShort)
                cleaned.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                cleaned.any(::isForbidden) -> Result.failure(Failure.InvalidCharacters)
                else -> Result.success(AddressLine(cleaned))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): AddressLine = AddressLine(value)

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char == '<' || char == '>'
    }

    // Always the `addressLine` field of the contract's AddressInput (under `newAddress.` at checkout).
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "addressLine", meta = meta) {
        data object TooShort : Failure("ADDRESS_LINE_TOO_SHORT", mapOf("minLength" to MIN_LENGTH))
        data object TooLong : Failure("ADDRESS_LINE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("ADDRESS_LINE_INVALID")
    }
}
