package com.kiva.application.usecase.account.usecase.command

import com.kiva.application.util.MockTransactionManager
import com.kiva.common.patch.Patch
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.repository.UserRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals

/** What UpdateMyProfileUc decides before it touches anything. The edit itself is tested on the database (WebV1MeUpdateOnDatabaseTest). */
class UpdateMyProfileUcTest {

    private val transactions = MockTransactionManager()
    private val useCase = UpdateMyProfileUc(transactions, mockk<UserRepository>(), Clock.systemUTC())
    private val me = UserId.constructFromPersisted(1024)

    @Test
    fun `a birth date that isn't a date is refused on its field, before any transaction`() = runTest {
        val failure = useCase.execute(UpdateMyProfileUc.Cmd(me, birthDate = Patch.Set("10/09/1996"))).failureOrNull()

        assertEquals("VALIDATION_ERROR", failure?.code)
        assertEquals(listOf("birthDate" to "BIRTH_DATE_INVALID"), failure?.causes?.map { it.field to it.code })
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `a command shows whose profile and which fields were sent, never their values`() {
        val command = UpdateMyProfileUc.Cmd(
            me, firstName = Patch.Set("سارا"), email = Patch.Set("sara@x.com"), defaultMessengerPhone = Patch.Set("09351112233"),
        )

        assertEquals("Cmd(user=1024, sent=[firstName, email, defaultMessengerPhone])", command.toString())
    }
}
