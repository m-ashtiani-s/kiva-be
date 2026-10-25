package com.kiva.domain.aggregate.newsletter.model.valueobject

import java.security.SecureRandom
import java.util.random.RandomGenerator

/**
 * The secret of a subscriber's unsubscribe link (`nl_` and 32 Latin letters and digits; roadmap phase 13, decision 13):
 * made when the email first subscribes and kept with it, so every welcome mail's link works.
 */
@JvmInline
value class UnsubscribeToken private constructor(val value: String) {
    init {
        require(FORMAT.matches(value)) { "Invalid UnsubscribeToken of length ${value.length}" }
    }

    override fun toString(): String = "UnsubscribeToken(***)"

    companion object {
        private val FORMAT = Regex("nl_[0-9A-Za-z]{32}")
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /** A token as it came in a link; null when it isn't of the shape (UNSUBSCRIBE_TOKEN_INVALID is the caller's). */
        fun of(raw: String): UnsubscribeToken? = raw.trim().takeIf { FORMAT.matches(it) }?.let { UnsubscribeToken(it) }

        /** A new random one; tests give their own [random]. */
        fun random(random: RandomGenerator = SecureRandom()): UnsubscribeToken =
            UnsubscribeToken("nl_" + (1..32).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(""))

        fun constructFromPersisted(value: String): UnsubscribeToken = UnsubscribeToken(value)
    }
}

enum class SubscriptionStatus {
    ACTIVE,
    UNSUBSCRIBED,
    ;

    companion object {
        fun of(raw: String): SubscriptionStatus? = entries.firstOrNull { it.name == raw.trim() }
    }
}

/**
 * Where on the site the subscription came from (`footer`): at most 30 lowercase Latin letters, digits, `_` and `-`
 * (decision 12). Anything else is not refused, only dropped.
 */
object SubscriptionSource {
    const val MAX_LENGTH = 30
    private val FORMAT = Regex("[a-z0-9_-]{1,$MAX_LENGTH}")

    fun of(raw: String?): String? = raw?.trim()?.takeIf { FORMAT.matches(it) }
}
