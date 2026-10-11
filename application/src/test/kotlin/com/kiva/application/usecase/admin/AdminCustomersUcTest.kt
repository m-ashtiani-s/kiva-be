package com.kiva.application.usecase.admin

import com.kiva.application.port.SecurityEventLog
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.AdminCustomerRepository.CustomerSearch
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.repository.UserRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals

/**
 * What the customers page's use cases decide before the database: the search they look for, and the input they refuse.
 * The roles change itself is tested on the database (WebV1AdminCustomersOnDatabaseTest).
 */
class AdminCustomersUcTest {

    private val transactions = MockTransactionManager()
    private val pageable = Pageable.makeNew(page = 1, size = 20).getOrNull() ?: error("no pageable")

    /** Remembers the search it was asked for; finds nobody. */
    private class RecordingCustomers : AdminCustomerRepository {
        var searched: CustomerSearch? = null

        override fun find(search: CustomerSearch, pageable: Pageable): Page<User> {
            searched = search
            return Page(emptyList(), pageable.page, pageable.size, 0)
        }

        override fun findById(id: UserId): User? = null
    }

    private suspend fun searchFor(q: String?): CustomerSearch? {
        val customers = RecordingCustomers()
        FindCustomersUc(transactions, customers).execute(FindCustomersUc.Qry(pageable, q))
        return customers.searched
    }

    @Test
    fun `no q, or a blank one, lists everyone`() = runTest {
        assertEquals(CustomerSearch.EVERYONE, searchFor(null))
        assertEquals(CustomerSearch.EVERYONE, searchFor("   "))
    }

    @Test
    fun `a whole mobile number in any form looks for that phone exactly`() = runTest {
        val phone = IranianMobileNumber.constructFromPersisted("09121234567")

        assertEquals(CustomerSearch(phone = phone), searchFor("+98 912 123 4567"))
        assertEquals(CustomerSearch(phone = phone), searchFor("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test
    fun `anything else is words, normalized as names and phones are stored`() = runTest {
        assertEquals(CustomerSearch(words = listOf("علی", "رضایی")), searchFor("  علي  رضايي "))
        assertEquals(CustomerSearch(words = listOf("0912")), searchFor("۰۹۱۲"))
        assertEquals(CustomerSearch(words = listOf("sara")), searchFor("Sara"))
    }

    @Test
    fun `a q over 100 characters is refused on the q field`() = runTest {
        val failure = FindCustomersUc(transactions, RecordingCustomers()).execute(FindCustomersUc.Qry(pageable, "س".repeat(101))).failureOrNull()

        assertEquals(listOf("q" to "SEARCH_QUERY_TOO_LONG"), failure?.causes?.map { it.field to it.code })
        assertEquals(0, transactions.transactionsOpened)
    }

    // Strict mocks: any call to them fails the test, so "refused before anything" is checked too.
    private val setUserRoles = SetUserRolesUc(transactions, mockk<UserRepository>(), mockk<AuthSessionRepository>(), mockk<SecurityEventLog>(), Clock.systemUTC())
    private val admin = UserId.constructFromPersisted(1)

    @Test
    fun `an unknown role is refused where it is in the list, before any transaction`() = runTest {
        val failure = setUserRoles.execute(SetUserRolesUc.Cmd("1024", listOf("OPERATOR", "OWNER", "admin"), by = admin)).failureOrNull()

        assertEquals("VALIDATION_ERROR", failure?.code)
        assertEquals(listOf("roles.1" to "ROLE_INVALID", "roles.2" to "ROLE_INVALID"), failure?.causes?.map { it.field to it.code })
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `a customer id that isn't one is NOT_FOUND, before any transaction`() = runTest {
        listOf("abc", "0", "-5", "").forEach { id ->
            assertEquals(Result.failure(SetUserRolesUc.Failure.NotFound), setUserRoles.execute(SetUserRolesUc.Cmd(id, listOf("OPERATOR"), by = admin)), id)
        }
        assertEquals(0, transactions.transactionsOpened)
    }
}
