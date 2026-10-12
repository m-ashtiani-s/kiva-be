package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.text.normalizePersianLetters

/**
 * The one rule of the free texts a shopper adds to an order (a note, a gift message, a messenger handle): optional,
 * shown back as written. Letters and spaces are normalized and line breaks become spaces ([normalizePersianLetters]);
 * digits stay as typed. Missing or blank is "none". Control characters are refused; `<` and `>` are not, since
 * «دوستت دارم <3» is a fine gift message and every screen escapes what it shows.
 */
internal object OptionalText {

    sealed interface Checked {
        /** [value] is null for "none". */
        data class Fine(val value: String?) : Checked

        data object TooLong : Checked

        data object InvalidCharacters : Checked
    }

    fun check(raw: String?, maxLength: Int): Checked {
        val cleaned = raw?.normalizePersianLetters().orEmpty()
        return when {
            cleaned.isEmpty() -> Checked.Fine(null)
            cleaned.length > maxLength -> Checked.TooLong
            cleaned.any(Char::isISOControl) -> Checked.InvalidCharacters
            else -> Checked.Fine(cleaned)
        }
    }

    /** The same rule as a check of a stored value: a bug when it fails. */
    fun isValid(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl)
}
