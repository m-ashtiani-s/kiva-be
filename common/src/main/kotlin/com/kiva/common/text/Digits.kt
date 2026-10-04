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
