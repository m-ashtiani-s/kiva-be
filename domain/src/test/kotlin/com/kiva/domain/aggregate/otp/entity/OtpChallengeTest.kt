package com.kiva.domain.aggregate.otp.entity

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.entity.OtpChallenge.Outcome
import com.kiva.domain.aggregate.otp.entity.OtpChallenge.Status
import com.kiva.domain.aggregate.otp.model.valueobject.CodeHash
import com.kiva.domain.aggregate.otp.model.valueobject.OtpChallengeId
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtpChallengeTest {

    private val sentOn = Instant.parse("2026-10-09T08:30:00Z")
    private val phone = IranianMobileNumber.constructFromPersisted("09121234567")
    private val newPhone = IranianMobileNumber.constructFromPersisted("09351112233")
    private val user = UserId.constructFromPersisted(1024)
    private val salt = Salt(ByteArray(16) { 9 })

    /** The hash of the code that was sent; [wrong] is the hash of any other code. */
    private val right = CodeHash(ByteArray(32) { 1 })
    private val wrong = CodeHash(ByteArray(32) { 2 })

    private fun after(duration: Duration): Instant = sentOn.plus(duration)

    private fun loginCode(): OtpChallenge =
        OtpChallenge.issue(OtpChallengeId.constructFromPersisted(1), OtpPurpose.LOGIN, phone, null, null, salt, right, sentOn)

    /** Tries [hashes] one after the other, each on what the previous try left, as saving and reading again would. */
    private fun OtpChallenge.tries(vararg hashes: CodeHash, at: Instant = after(Duration.ofSeconds(30))): Pair<OtpChallenge, List<Outcome>> {
        var challenge = this
        val outcomes = hashes.map { hash -> challenge.attempt(hash, at).also { challenge = it.challenge }.outcome }
        return challenge to outcomes
    }

    @Nested
    inner class Issuing {
        @Test
        fun `a new code is active for five minutes with no tries taken`() {
            val code = loginCode()

            assertEquals(Status.ACTIVE, code.statusAt(sentOn))
            assertEquals(0, code.failedAttempts)
            assertEquals(sentOn, code.createdOn)
            assertEquals(after(Duration.ofMinutes(5)), code.expiresOn)
            assertTrue(code.isOpen)
        }

        @Test
        fun `only a LOGIN code has no user`() {
            assertFailsWith<IllegalArgumentException> {
                OtpChallenge.issue(OtpChallengeId.constructFromPersisted(1), OtpPurpose.LOGIN, phone, user, null, salt, right, sentOn)
            }
            assertFailsWith<IllegalArgumentException> {
                OtpChallenge.issue(OtpChallengeId.constructFromPersisted(1), OtpPurpose.PHONE_CHANGE_NEW, newPhone, null, null, salt, right, sentOn)
            }
        }

        @Test
        fun `only the current phone's code of a phone change names the new phone it allows`() {
            val current = OtpChallenge.issue(
                OtpChallengeId.constructFromPersisted(1), OtpPurpose.PHONE_CHANGE_CURRENT, phone, user, newPhone, salt, right, sentOn,
            )
            assertEquals(newPhone, current.authorizesPhone)

            assertFailsWith<IllegalArgumentException> {
                OtpChallenge.issue(OtpChallengeId.constructFromPersisted(1), OtpPurpose.PHONE_CHANGE_CURRENT, phone, user, null, salt, right, sentOn)
            }
            assertFailsWith<IllegalArgumentException> {
                OtpChallenge.issue(OtpChallengeId.constructFromPersisted(1), OtpPurpose.LOGIN, phone, null, newPhone, salt, right, sentOn)
            }
        }
    }

    @Nested
    inner class Trying {
        @Test
        fun `the right code is accepted and consumed, and can't be used again`() {
            val (consumed, outcomes) = loginCode().tries(right, right)

            assertEquals(listOf(Outcome.Accepted, Outcome.Expired), outcomes)
            assertEquals(Status.CONSUMED, consumed.statusAt(sentOn))
            assertEquals(after(Duration.ofSeconds(30)), consumed.consumedOn)
            assertFalse(consumed.isOpen)
        }

        @Test
        fun `five wrong codes count down to exhausted`() {
            val (exhausted, outcomes) = loginCode().tries(wrong, wrong, wrong, wrong, wrong)

            assertEquals(listOf(Outcome.Wrong(4), Outcome.Wrong(3), Outcome.Wrong(2), Outcome.Wrong(1), Outcome.Exhausted), outcomes)
            assertEquals(5, exhausted.failedAttempts)
            assertEquals(Status.EXHAUSTED, exhausted.statusAt(sentOn))
        }

        @Test
        fun `once exhausted, even the right code is exhausted, and no more tries are counted`() {
            val (exhausted, outcomes) = loginCode().tries(wrong, wrong, wrong, wrong, wrong, right, wrong)

            assertEquals(listOf(Outcome.Exhausted, Outcome.Exhausted), outcomes.takeLast(2))
            assertEquals(5, exhausted.failedAttempts)
            assertNull(exhausted.consumedOn)
        }

        @Test
        fun `the right code after a few wrong ones is still accepted`() {
            val (_, outcomes) = loginCode().tries(wrong, wrong, right)

            assertEquals(listOf(Outcome.Wrong(4), Outcome.Wrong(3), Outcome.Accepted), outcomes)
        }

        @Test
        fun `the right code a second before five minutes is accepted, and five minutes and a second after is expired`() {
            assertEquals(Outcome.Accepted, loginCode().attempt(right, after(Duration.ofMinutes(5).minusSeconds(1))).outcome)

            val late = loginCode().attempt(right, after(Duration.ofMinutes(5).plusSeconds(1)))
            assertEquals(Outcome.Expired, late.outcome)
            assertNull(late.challenge.consumedOn)
        }

        @Test
        fun `at exactly five minutes the code has expired`() {
            assertEquals(Status.EXPIRED, loginCode().statusAt(after(Duration.ofMinutes(5))))
        }

        @Test
        fun `a wrong code after expiry takes no try`() {
            val late = loginCode().attempt(wrong, after(Duration.ofMinutes(6)))

            assertEquals(Outcome.Expired, late.outcome)
            assertEquals(0, late.challenge.failedAttempts)
        }

        @Test
        fun `a try changes only what it returns, never the challenge it was made on`() {
            val code = loginCode()

            code.attempt(wrong, sentOn)
            code.attempt(right, sentOn)

            assertEquals(0, code.failedAttempts)
            assertNull(code.consumedOn)
        }
    }

    @Nested
    inner class Replacing {
        @Test
        fun `a replaced code can't be used, even with the right code`() {
            val replaced = loginCode().replace(after(Duration.ofSeconds(130)))

            assertEquals(Status.REPLACED, replaced.statusAt(after(Duration.ofSeconds(131))))
            assertEquals(Outcome.Expired, replaced.attempt(right, after(Duration.ofSeconds(131))).outcome)
            assertFalse(replaced.isOpen)
        }

        @Test
        fun `an exhausted or expired code is still open, so the next code replaces it`() {
            val (exhausted, _) = loginCode().tries(wrong, wrong, wrong, wrong, wrong)

            assertTrue(exhausted.isOpen)
            assertEquals(Status.REPLACED, exhausted.replace(after(Duration.ofMinutes(3))).statusAt(after(Duration.ofMinutes(3))))
            assertEquals(Status.REPLACED, loginCode().replace(after(Duration.ofMinutes(10))).statusAt(after(Duration.ofMinutes(10))))
        }

        @Test
        fun `an accepted or already replaced code can't be replaced`() {
            val (consumed, _) = loginCode().tries(right)

            assertFailsWith<IllegalStateException> { consumed.replace(after(Duration.ofMinutes(1))) }
            assertFailsWith<IllegalStateException> { loginCode().replace(sentOn).replace(sentOn) }
        }
    }

    @Test
    fun `a stored challenge is read back as it was`() {
        val stored = OtpChallenge.constructFromPersisted(
            OtpChallengeId.constructFromPersisted(7), OtpPurpose.LOGIN, phone, null, null, salt, right,
            failedAttempts = 2, createdOn = sentOn, expiresOn = after(Duration.ofMinutes(5)), consumedOn = null, replacedOn = null,
        )

        assertEquals(Outcome.Wrong(2), stored.attempt(wrong, after(Duration.ofMinutes(1))).outcome)
    }

    @Test
    fun `stored tries beyond five are a bug`() {
        assertFailsWith<IllegalArgumentException> {
            OtpChallenge.constructFromPersisted(
                OtpChallengeId.constructFromPersisted(7), OtpPurpose.LOGIN, phone, null, null, salt, right,
                failedAttempts = 6, createdOn = sentOn, expiresOn = after(Duration.ofMinutes(5)), consumedOn = null, replacedOn = null,
            )
        }
    }

    @Test
    fun `toString shows no phone, salt or hash`() {
        assertEquals("OtpChallenge(id=1, purpose=LOGIN, failedAttempts=0)", loginCode().toString())
    }
}
