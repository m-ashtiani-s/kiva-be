package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.port.AccessTokens
import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SessionTokens
import com.kiva.application.util.MockTransactionManager
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What VerifyLoginCodeUc decides before it touches anything. Trying codes, the lock, the rollback trap and the session
 * are tested on the real database (WebV1AuthVerifyOnDatabaseTest).
 */
class VerifyLoginCodeUcTest {

    private val transactions = MockTransactionManager()

    // Strict mocks: any call to them fails the test, so "refused before anything" is checked too.
    private val useCase = VerifyLoginCodeUc(
        transactionManager = transactions,
        otpChallengeRepository = mockk<OtpChallengeRepository>(),
        userRepository = mockk<UserRepository>(),
        authSessionRepository = mockk<AuthSessionRepository>(),
        otpCodes = mockk<OtpCodes>(),
        sessionTokens = mockk<SessionTokens>(),
        accessTokens = mockk<AccessTokens>(),
        securityEventLog = mockk<SecurityEventLog>(),
        clock = Clock.systemUTC(),
    )

    private fun command(phone: String = "09121234567", code: String = "48213") =
        VerifyLoginCodeUc.Cmd(phone = phone, code = code, deviceName = null, userAgent = null, ip = null)

    private suspend fun problemsOf(command: VerifyLoginCodeUc.Cmd): List<Pair<String?, String>> {
        val failure = useCase.execute(command).failureOrNull() as? VerifyLoginCodeUc.Failure.InvalidInput
            ?: error("expected the input to be refused")
        return failure.problems.map { it.field to it.code }
    }

    @Test
    fun `a code that isn't 5 digits is refused on the code field, before any transaction, so it takes no try`() = runTest {
        assertEquals(listOf("code" to "OTP_CODE_FORMAT_INVALID"), problemsOf(command(code = "4821")))
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `a wrong phone and a wrong code format come back together`() = runTest {
        assertEquals(
            listOf("phone" to "PHONE_INVALID", "code" to "OTP_CODE_FORMAT_INVALID"),
            problemsOf(command(phone = "0912", code = "abcde")),
        )
    }

    @Test
    fun `a command shows neither the phone nor the code`() {
        val printed = "${command(code = "48213")}"

        assertFalse("09121234567" in printed)
        assertFalse("48213" in printed)
    }
}
