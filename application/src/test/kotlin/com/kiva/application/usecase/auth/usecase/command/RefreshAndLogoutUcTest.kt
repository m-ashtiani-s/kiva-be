package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.port.AccessTokens
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SessionTokens
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Refresh and logout without a cookie decide before touching anything. Rotation, grace, theft and logout are tested on
 * the real database (WebV1AuthRefreshOnDatabaseTest).
 */
class RefreshAndLogoutUcTest {

    private val transactions = MockTransactionManager()

    // Strict mocks: any call to them fails the test.
    private val refresh = RefreshSessionUc(
        transactions, mockk<AuthSessionRepository>(), mockk<UserRepository>(), mockk<SessionTokens>(), mockk<AccessTokens>(),
        mockk<SecurityEventLog>(), Clock.systemUTC(),
    )
    private val logout = LogoutUc(transactions, mockk<AuthSessionRepository>(), mockk<SessionTokens>(), mockk<SecurityEventLog>(), Clock.systemUTC())

    @Test
    fun `a refresh without a cookie is REFRESH_TOKEN_INVALID, before any transaction`() = runTest {
        assertEquals(Result.failure(RefreshSessionUc.Failure.Invalid), refresh.execute(RefreshSessionUc.Cmd(refreshToken = null, ip = null)))
        assertEquals(Result.failure(RefreshSessionUc.Failure.Invalid), refresh.execute(RefreshSessionUc.Cmd(refreshToken = " ", ip = null)))
        assertEquals("REFRESH_TOKEN_INVALID", RefreshSessionUc.Failure.Invalid.code)
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `a logout without a cookie is done, before any transaction`() = runTest {
        assertEquals(Result.success(Unit), logout.execute(LogoutUc.Cmd(refreshToken = null, allDevices = true, ip = null)))
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `their commands never show the token`() {
        val token = "Q2xhdWRlIHdhcyBoZXJlIGFuZCBsZWZ0IGEgdG9rZW4x"

        assertFalse(token in "${RefreshSessionUc.Cmd(token, ip = null)}")
        assertFalse(token in "${LogoutUc.Cmd(token, allDevices = false, ip = null)}")
    }
}
