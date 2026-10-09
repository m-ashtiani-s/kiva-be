package com.kiva.domain.aggregate.otp.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import java.time.Duration
import java.time.Instant

/**
 * The numbers of one-time codes, from the contract's table of limits, and the rules on them. Kept here as constants,
 * not in config: a limit changed by mistake in a config file would weaken the defence against guessing without anyone
 * noticing.
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

    /**
     * Requests for a code from one IP address in [IP_WINDOW]. Deliberately large: many Iranian customers share an
     * address behind their operator's NAT. The limits per phone and the shop's hourly cap are the real defence.
     */
    const val MAX_SENDS_PER_IP = 20
    val IP_WINDOW: Duration = Duration.ofMinutes(10)

    val HOUR: Duration = Duration.ofHours(1)
    val DAY: Duration = Duration.ofHours(24)

    /** One code sent to a phone, as the send limits see it. */
    data class SentCode(val purpose: OtpPurpose, val sentOn: Instant, val failedAttempts: Int)

    /**
     * Why a new [purpose] code may not be sent to a phone now, or null if it may.
     *
     * @param sent the phone's codes of the last [DAY], any purpose. Older ones may be in it; they don't count.
     *
     * Checked in this order, so the answer is the most specific one: the same purpose within [RESEND_AFTER], then
     * codes in the last hour, codes in the last day, and wrong codes in the last day.
     */
    fun refusalToSend(purpose: OtpPurpose, sent: List<SentCode>, now: Instant): SendRefusal? {
        val lastOfPurpose = sent.filter { it.purpose == purpose }.maxOfOrNull { it.sentOn }
        if (lastOfPurpose != null) {
            val wait = Duration.between(now, lastOfPurpose.plus(RESEND_AFTER))
            if (wait > Duration.ZERO) return SendRefusal.ResendTooSoon(wait)
        }

        val times = sent.map { it.sentOn }
        waitUntilUnder(times, MAX_SENDS_PER_HOUR, HOUR, now)?.let { return SendRefusal.TooManyCodes(it) }
        waitUntilUnder(times, MAX_SENDS_PER_DAY, DAY, now)?.let { return SendRefusal.TooManyCodes(it) }
        return wrongCodesWait(sent, now)?.let { SendRefusal.TooManyWrongCodes(it) }
    }

    /**
     * The shop's cap on SMS in an hour, all phones together: a safety valve for the SMS bill when someone sends codes
     * to thousands of numbers. Null while under it.
     *
     * @param sentLastHour codes sent to anyone in the last [HOUR]; [earliest] is the oldest of them.
     */
    fun refusalForShopCap(sentLastHour: Int, earliest: Instant?, cap: Int, now: Instant): SendRefusal? {
        if (sentLastHour < cap || earliest == null) return null
        return SendRefusal.ShopHourlyCapReached(Duration.between(now, earliest.plus(HOUR)))
    }

    /** How long until fewer than [max] of [times] are within the last [window]; null if fewer already. */
    private fun waitUntilUnder(times: List<Instant>, max: Int, window: Duration, now: Instant): Duration? {
        val inWindow = times.filter { it.isAfter(now.minus(window)) }.sorted()
        if (inWindow.size < max) return null
        // When this one leaves the window, max - 1 are left.
        val mustLeave = inWindow[inWindow.size - max]
        return Duration.between(now, mustLeave.plus(window))
    }

    /** How long until the wrong codes of the last [DAY] add up to fewer than [MAX_WRONG_PER_DAY]; null if they do already. */
    private fun wrongCodesWait(sent: List<SentCode>, now: Instant): Duration? {
        val withWrong = sent.filter { it.failedAttempts > 0 && it.sentOn.isAfter(now.minus(DAY)) }.sortedBy { it.sentOn }
        var wrong = withWrong.sumOf { it.failedAttempts }
        for (code in withWrong) {
            if (wrong < MAX_WRONG_PER_DAY) break
            wrong -= code.failedAttempts
            if (wrong < MAX_WRONG_PER_DAY) return Duration.between(now, code.sentOn.plus(DAY))
        }
        return null
    }

    /**
     * No code now. Every refusal is a 429 and tells the client how long to wait, rounded up to a whole second
     * (`retryAfterSeconds`, also sent as the Retry-After header).
     */
    sealed class SendRefusal(code: String, retryAfter: Duration) :
        ResultFailure(code, FailureKind.RATE_LIMITED, meta = mapOf("retryAfterSeconds" to wholeSecondsOf(retryAfter))) {

        abstract val retryAfter: Duration

        /** A code of the same purpose went to this phone less than [RESEND_AFTER] ago. */
        data class ResendTooSoon(override val retryAfter: Duration) : SendRefusal("OTP_RESEND_TOO_SOON", retryAfter)

        /** This phone got [MAX_SENDS_PER_HOUR] codes in the last hour, or [MAX_SENDS_PER_DAY] in the last day. */
        data class TooManyCodes(override val retryAfter: Duration) : SendRefusal("RATE_LIMITED", retryAfter)

        /** [MAX_WRONG_PER_DAY] wrong codes were typed for this phone in the last day. */
        data class TooManyWrongCodes(override val retryAfter: Duration) : SendRefusal("RATE_LIMITED", retryAfter)

        /** The shop sent its cap of SMS in the last hour. */
        data class ShopHourlyCapReached(override val retryAfter: Duration) : SendRefusal("RATE_LIMITED", retryAfter)
    }

    private fun wholeSecondsOf(duration: Duration): Long = ((duration.toMillis() + 999) / 1000).coerceAtLeast(1)
}
