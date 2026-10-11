package com.kiva.domain.aggregate.otp.model.policy

import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy.Refusal
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy.SentCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose.LOGIN
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose.PHONE_CHANGE_NEW
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The limits on sending codes, from a phone's codes of the last day. */
class OtpSendLimitsTest {

    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private fun ago(duration: Duration) = now.minus(duration)
    private fun minutes(n: Long) = Duration.ofMinutes(n)
    private fun login(sentAgo: Duration, failedAttempts: Int = 0) = SentCode(LOGIN, ago(sentAgo), failedAttempts)

    private fun refusal(vararg sent: SentCode) = OtpPolicy.refusalToSend(LOGIN, sent.toList(), now)

    @Test
    fun `a phone that got nothing may get a code`() {
        assertNull(refusal())
    }

    @Test
    fun `the same purpose again within 120 seconds is OTP_RESEND_TOO_SOON, with the seconds left`() {
        val refused = refusal(login(Duration.ofSeconds(30)))

        assertEquals(Refusal.ResendTooSoon(Duration.ofSeconds(90)), refused)
        assertEquals("OTP_RESEND_TOO_SOON", refused?.code)
        assertEquals(mapOf<String, Any>("retryAfterSeconds" to 90L), refused?.meta)
    }

    @Test
    fun `after 120 seconds the same purpose may get a new code`() {
        assertNull(refusal(login(Duration.ofSeconds(120))))
    }

    @Test
    fun `a code of another purpose doesn't make a login code wait`() {
        assertNull(refusal(SentCode(PHONE_CHANGE_NEW, ago(Duration.ofSeconds(10)), 0)))
    }

    @Test
    fun `the sixth code in an hour is refused until the oldest of them is an hour old`() {
        val fiveInAnHour = listOf(50L, 40, 30, 20, 10).map { login(minutes(it)) }.toTypedArray()

        val refused = refusal(*fiveInAnHour)

        assertEquals(Refusal.TooManyCodes(minutes(10)), refused)
        assertEquals("RATE_LIMITED", refused?.code)
        assertNull(refusal(*fiveInAnHour.drop(1).toTypedArray()))
    }

    @Test
    fun `codes of every purpose count towards the hour`() {
        val sent = listOf(50L, 40, 30, 20).map { login(minutes(it)) } + SentCode(PHONE_CHANGE_NEW, ago(minutes(5)), 0)

        assertEquals(Refusal.TooManyCodes(minutes(10)), OtpPolicy.refusalToSend(LOGIN, sent, now))
    }

    @Test
    fun `the eleventh code in a day is refused until the oldest of them is a day old`() {
        val tenInADay = (1L..10).map { login(Duration.ofHours(it * 2)) }.toTypedArray()

        assertEquals(Refusal.TooManyCodes(Duration.ofHours(4)), refusal(*tenInADay))
    }

    @Test
    fun `codes older than a day don't count`() {
        val old = (25L..35).map { login(Duration.ofHours(it)) }.toTypedArray()

        assertNull(refusal(*old))
    }

    @Test
    fun `ten wrong codes in a day, over several codes, stop new codes until enough of them are a day old`() {
        // 4 + 3 + 3 wrong tries: the 4 must leave the window for the count to go under 10.
        val refused = refusal(login(Duration.ofHours(20), 4), login(Duration.ofHours(10), 3), login(Duration.ofHours(5), 3))

        assertEquals(Refusal.TooManyWrongCodes(Duration.ofHours(4)), refused)
        assertEquals("RATE_LIMITED", refused?.code)
    }

    @Test
    fun `nine wrong codes in a day don't stop anything`() {
        assertNull(refusal(login(Duration.ofHours(20), 5), login(Duration.ofHours(10), 4)))
    }

    @Test
    fun `ten wrong codes in a day stop every try too, however few codes were sent`() {
        val sent = listOf(login(Duration.ofHours(20), 5), login(Duration.ofHours(10), 5))

        assertEquals(Refusal.TooManyWrongCodes(Duration.ofHours(4)), OtpPolicy.refusalToVerify(sent, now))
        assertNull(OtpPolicy.refusalToVerify(listOf(login(Duration.ofHours(20), 5), login(Duration.ofHours(10), 4)), now))
    }

    @Test
    fun `the most specific answer comes first`() {
        val fiveInAnHourLastOneJustNow = listOf(50L, 40, 30, 20).map { login(minutes(it)) } + login(Duration.ofSeconds(20))

        assertEquals(Refusal.ResendTooSoon(Duration.ofSeconds(100)), OtpPolicy.refusalToSend(LOGIN, fiveInAnHourLastOneJustNow, now))
    }

    @Test
    fun `the wait is rounded up to a whole second, and is at least one`() {
        assertEquals(mapOf<String, Any>("retryAfterSeconds" to 2L), Refusal.TooManyCodes(Duration.ofMillis(1_001)).meta)
        assertEquals(mapOf<String, Any>("retryAfterSeconds" to 1L), Refusal.TooManyCodes(Duration.ZERO).meta)
    }

    @Test
    fun `at the shop's hourly cap no code goes out until the oldest of the hour is an hour old`() {
        assertNull(OtpPolicy.refusalForShopCap(sentLastHour = 299, earliest = ago(minutes(50)), cap = 300, now = now))
        assertEquals(
            Refusal.ShopHourlyCapReached(minutes(10)),
            OtpPolicy.refusalForShopCap(sentLastHour = 300, earliest = ago(minutes(50)), cap = 300, now = now),
        )
    }
}
