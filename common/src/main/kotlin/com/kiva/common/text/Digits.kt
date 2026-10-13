package com.kiva.common.text

/**
 * Turns Persian (۰-۹) and Arabic (٠-٩) digits into English digits (0-9). Everything else is left as is.
 *
 * Users type numbers with whatever keyboard they have: "۰۹۱۲" from a Persian keyboard looks like a number
 * to a person but is not "0912" to the computer. Call this before checking or storing any number from a user.
 */
fun String.normalizeDigits(): String = buildString(length) {
    for (char in this@normalizeDigits) {
        append(
            when (char) {
                in '۰'..'۹' -> '0' + (char - '۰')
                in '٠'..'٩' -> '0' + (char - '٠')
                else -> char
            },
        )
    }
}

private const val ZWNJ = '‌' // نیم‌فاصله

/**
 * Makes Persian text from different keyboards comparable, for search and for unique names:
 * - Arabic `ي` / `ى` -> Persian `ی`, Arabic `ك` -> Persian `ک`
 * - Persian/Arabic digits -> English digits
 * - repeated or misplaced half-spaces (ZWNJ) cleaned, repeated spaces collapsed, ends trimmed
 *
 * Without this, "كيف" typed on an Arabic keyboard would never find "کیف".
 */
fun String.normalizePersian(): String = normalizeDigits().normalizePersianLetters()

/**
 * [normalizePersian] without the digits: for text the shop shows back as the customer wrote it, such as an address
 * line, where «پلاک ۱۲» must stay «پلاک ۱۲». The contract normalizes digits only in data fields (numbers, codes, amounts).
 */
fun String.normalizePersianLetters(): String =
    replace('ي', 'ی')
        .replace('ى', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("$ZWNJ+"), ZWNJ.toString())       // several half-spaces in a row -> one
        .replace(Regex("$ZWNJ*\\s+$ZWNJ*"), " ")       // a half-space touching a space is just a space
        .trim(' ', ZWNJ)
