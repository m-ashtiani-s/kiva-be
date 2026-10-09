package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SmsSender
import com.kiva.application.util.MockTransactionManager
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What SendLoginCodeUc decides before it touches anything. The limits, the lock and the SMS are tested on the real
 * database (WebV1AuthSendOnDatabaseTest).
 */
class SendLoginCodeUcTest {

    private val transactions = MockTransactionManager()

    // Strict mocks: any call to them fails the test, so "refused before anything" is checked too.
    private val useCase = SendLoginCodeUc(
        transactionManager = transactions,
        otpChallengeRepository = mockk<OtpChallengeRepository>(),
        otpCodes = mockk<OtpCodes>(),
        smsSender = mockk<SmsSender>(),
        securityEventLog = mockk<SecurityEventLog>(),
        clock = Clock.systemUTC(),
        maxSmsPerHour = 300,
    )

    private suspend fun problemsOf(command: SendLoginCodeUc.Cmd): List<Pair<String?, String>> {
        val failure = useCase.execute(command).failureOrNull() as? SendLoginCodeUc.Failure.InvalidInput
            ?: error("expected the input to be refused")
        return failure.problems.map { it.field to it.code }
    }

    @Test
    fun `a wrong phone is refused on the phone field, before any transaction`() = runTest {
        assertEquals(listOf("phone" to "PHONE_INVALID"), problemsOf(SendLoginCodeUc.Cmd("0912", purpose = null, ip = null)))
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `only a login code can be asked for here`() = runTest {
        assertEquals(
            listOf("purpose" to "OTP_PURPOSE_INVALID"),
            problemsOf(SendLoginCodeUc.Cmd("09121234567", purpose = "PHONE_CHANGE_NEW", ip = null)),
        )
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `both problems come back together`() = runTest {
        assertEquals(
            listOf("phone" to "PHONE_REQUIRED", "purpose" to "OTP_PURPOSE_INVALID"),
            problemsOf(SendLoginCodeUc.Cmd("", purpose = "DELETE_ACCOUNT", ip = null)),
        )
    }

    /** So the answer can't depend on whether the phone has an account. */
    @Test
    fun `it can't look at users at all`() {
        val needs = SendLoginCodeUc::class.java.constructors.flatMap { it.parameterTypes.toList() }

        assertTrue(needs.isNotEmpty())
        assertFalse(UserRepository::class.java in needs)
    }

    @Test
    fun `a command never shows the typed phone`() {
        val command = SendLoginCodeUc.Cmd("09121234567", purpose = null, ip = "1.2.3.4")

        assertFalse("09121234567" in "$command")
        assertFalse("1234567" in "$command")
    }
}
