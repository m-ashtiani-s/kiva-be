package com.kiva.domain.aggregate.otp.model.policy

import java.time.Duration

/**
 * The numbers of one-time codes, from the contract's table of limits. Kept here as constants, not in config: a limit
 * changed by mistake in a config file would weaken the defence against guessing without anyone noticing.
 *
 * Why these numbers: a 5-digit code has 100,000 values. With 5 tries per code and at most 10 wrong codes a day per
 * phone, someone guessing has about a 0.3% chance in a month; without the daily cap it would be about 16%.
 */
object OtpPolicy {

    /** Digits in a code; `00042` is a code too. */
    const val CODE_LENGTH = 5

    /** How long a code can be used after it is sent. */
    val LIFETIME: Duration = Duration.ofMinutes(5)

    /** Wrong tries one code takes; the last one ends it (exhausted). */
    const val MAX_ATTEMPTS = 5

    /** A new code for the same phone and purpose only after this long. */
    val RESEND_AFTER: Duration = Duration.ofSeconds(120)

    /** Codes sent to one phone, all purposes together. */
    const val MAX_SENDS_PER_HOUR = 5
    const val MAX_SENDS_PER_DAY = 10

    /** Wrong codes typed for one phone in 24 hours, over all its codes; then no login for that phone until the window moves. */
    const val MAX_WRONG_PER_DAY = 10
}
