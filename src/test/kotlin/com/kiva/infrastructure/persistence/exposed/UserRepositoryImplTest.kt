package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.patch.Patch
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.ProfilePatch
import com.kiva.domain.aggregate.user.model.valueobject.Role.ADMIN
import com.kiva.domain.aggregate.user.model.valueobject.Role.OPERATOR
import com.kiva.domain.aggregate.user.model.valueobject.Role.SUPPORT
import com.kiva.domain.aggregate.user.repository.PhoneTakenException
import com.kiva.domain.aggregate.user.repository.UserRepository.FoundOrRegistered
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Users stored in kiva_test and read back, each step in its own transaction as in the app.
 * Every test phone starts with 0990, and those users are deleted after each test.
 */
class UserRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = UserRepositoryImpl()

    private val now = Instant.parse("2026-10-09T08:30:00Z")
    private val today = LocalDate.of(2026, 10, 9)
    private val admin = UserId.constructFromPersisted(1)

    @AfterEach
    fun deleteTestUsers() {
        transaction(db) { UserTbl.deleteWhere { UserTbl.phone like "0990%" } }
    }

    private fun phone(n: Int) = IranianMobileNumber.constructFromPersisted("0990" + n.toString().padStart(7, '0'))

    private fun register(phone: IranianMobileNumber): FoundOrRegistered = transaction(db) { repository.findOrRegister(phone, now) }

    private fun read(id: UserId): User? = transaction(db) { repository.findById(id) }

    private fun save(user: User) = transaction(db) { repository.save(user) }

    private fun User.with(patch: ProfilePatch): User = updateProfile(patch, today).getOrNull() ?: fail("profile refused: $patch")

    private fun User.snapshot(): List<Any?> = listOf(
        id, phone, firstName, lastName, email, birthDate, gender, defaultMessenger, defaultMessengerPhone,
        marketingSmsOptIn, roles, createdOn, version,
    )

    private val fullProfile = ProfilePatch(
        firstName = Patch.Set("سارا"),
        lastName = Patch.Set("محمدی"),
        email = Patch.Set("sara@x.com"),
        birthDate = Patch.Set(LocalDate.of(1996, 9, 10)),
        gender = Patch.Set("FEMALE"),
        defaultMessenger = Patch.Set("TELEGRAM"),
        defaultMessengerPhone = Patch.Set("09351112233"),
        marketingSmsOptIn = Patch.Set(true),
    )

    @Test
    fun `a new account is read back as it was made, empty fields included`() {
        val registered = register(phone(1))

        assertTrue(registered.isNew)
        assertEquals(registered.user.snapshot(), read(registered.user.id)?.snapshot())
        assertEquals(registered.user.snapshot(), transaction(db) { repository.findByPhone(phone(1)) }?.snapshot())
    }

    @Test
    fun `a full profile and panel roles are saved and read back field by field`() {
        val user = register(phone(2)).user
        val changed = user.with(fullProfile).setPanelRoles(listOf(SUPPORT, OPERATOR), by = admin).getOrNull() ?: fail()

        save(changed)

        val stored = read(user.id) ?: fail("not stored")
        assertEquals(changed.snapshot().dropLast(1), stored.snapshot().dropLast(1))
        assertEquals(1L, stored.version)
    }

    @Test
    fun `roles are stored in the contract's order`() {
        val user = register(phone(3)).user
        save(user.setPanelRoles(listOf(SUPPORT, ADMIN), by = admin).getOrNull() ?: fail())

        val stored = transaction(db) { UserTbl.select(UserTbl.roles).where { UserTbl.id eq user.id.value }.single()[UserTbl.roles] }
        assertEquals(listOf("CUSTOMER", "ADMIN", "SUPPORT"), stored)
    }

    @Test
    fun `cleared fields are stored as nulls`() {
        val user = register(phone(4)).user
        save(user.with(fullProfile))
        val filled = read(user.id) ?: fail()

        save(
            filled.with(
                ProfilePatch(
                    firstName = Patch.Set(null),
                    email = Patch.Set(null),
                    birthDate = Patch.Set(null),
                    defaultMessenger = Patch.Set(null),
                    defaultMessengerPhone = Patch.Set(null),
                ),
            ),
        )

        val cleared = read(user.id) ?: fail()
        assertNull(cleared.firstName)
        assertNull(cleared.email)
        assertNull(cleared.birthDate)
        assertNull(cleared.defaultMessenger)
        assertNull(cleared.defaultMessengerPhone)
        assertEquals("محمدی", cleared.lastName?.value)
        assertEquals(2L, cleared.version)
    }

    @Test
    fun `an unknown id or phone is null`() {
        assertNull(read(UserId.constructFromPersisted(Long.MAX_VALUE)))
        assertNull(transaction(db) { repository.findByPhone(phone(5)) })
    }

    @Test
    fun `the next login of the same phone finds the same account`() {
        val first = register(phone(6))
        val second = register(phone(6))

        assertFalse(second.isNew)
        assertEquals(first.user.id, second.user.id)
    }

    @Test
    fun `ten first logins of one phone at the same moment make one account, and only one of them is new`() {
        val phone = phone(7)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        val results = try {
            val logins = List(10) { pool.submit<FoundOrRegistered> { start.await(); register(phone) } }
            start.countDown()
            logins.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, results.count { it.isNew })
        assertEquals(1, results.map { it.user.id }.toSet().size)
        assertEquals(1L, transaction(db) { UserTbl.selectAll().where { UserTbl.phone eq phone.value }.count() })
    }

    /**
     * The insert itself, not the lookup before it, decides: the other login has inserted the account but not committed
     * yet, so this one doesn't see it, inserts too, and has to wait. Only when it is seen waiting does the other commit.
     */
    @Test
    fun `a login that inserts while another one's account is not committed yet waits, then gets that account`() {
        val phone = phone(8)
        val otherInserted = CountDownLatch(1)
        val otherMayCommit = CountDownLatch(1)
        var other: FoundOrRegistered? = null
        var mine: FoundOrRegistered? = null

        val otherLogin = thread {
            transaction(db) {
                other = repository.findOrRegister(phone, now)
                otherInserted.countDown()
                otherMayCommit.await(30, TimeUnit.SECONDS)
            }
        }
        otherInserted.await(30, TimeUnit.SECONDS)
        val myLogin = thread { mine = register(phone) }
        try {
            awaitSomeoneWaitingForALock()
        } finally {
            otherMayCommit.countDown()
            otherLogin.join()
            myLogin.join()
        }

        assertEquals(true, other?.isNew)
        assertEquals(false, mine?.isNew)
        assertEquals(other?.user?.id, mine?.user?.id)
    }

    private fun awaitSomeoneWaitingForALock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            // A new transaction each time: within one, Postgres keeps showing the activity it saw first.
            val waiting = transaction(db) {
                exec("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'") {
                    it.next()
                    it.getInt(1)
                } ?: 0
            }
            if (waiting > 0) return
            Thread.sleep(20)
        }
        fail("the second login never waited for the first one's insert")
    }

    @Test
    fun `of two saves of the same read the second is refused, and the first one's change stays`() {
        val user = register(phone(9)).user
        val mine = user.with(ProfilePatch(firstName = Patch.Set("سارا")))
        val theirs = user.with(ProfilePatch(firstName = Patch.Set("مریم")))

        save(mine)
        val refused = assertFailsWith<ConcurrentChangeException> { save(theirs) }

        assertEquals("USER", refused.aggregateName.value)
        assertEquals(user.id.value.toString(), refused.aggregateId.value)
        assertEquals("سارا", read(user.id)?.firstName?.value)
        assertEquals(1L, read(user.id)?.version)
    }

    @Test
    fun `a change to the user read again after a save is saved`() {
        val user = register(phone(10)).user
        save(user.with(ProfilePatch(firstName = Patch.Set("سارا"))))

        save((read(user.id) ?: fail()).with(ProfilePatch(lastName = Patch.Set("محمدی"))))

        val stored = read(user.id) ?: fail()
        assertEquals("سارا" to "محمدی", stored.firstName?.value to stored.lastName?.value)
        assertEquals(2L, stored.version)
    }

    @Test
    fun `a phone another account has can't be saved, and the save says whose phone it was`() {
        val first = register(phone(12)).user
        register(phone(13))

        val refused = assertFailsWith<PhoneTakenException> { save(first.changePhone(phone(13)).getOrNull() ?: fail()) }

        assertEquals(phone(13), refused.phone)
        assertEquals(phone(12), read(first.id)?.phone)
    }

    @Test
    fun `a new phone that is free is saved`() {
        val user = register(phone(14)).user

        save(user.changePhone(phone(15)).getOrNull() ?: fail())

        assertEquals(phone(15), read(user.id)?.phone)
        assertNull(transaction(db) { repository.findByPhone(phone(14)) })
    }

    @Test
    fun `there is an admin once a user has the ADMIN role, and not before`() {
        val user = register(phone(11)).user
        assertFalse(transaction(db) { repository.hasAnyAdmin() })

        save(user.setPanelRoles(listOf(OPERATOR), by = admin).getOrNull() ?: fail())
        assertFalse(transaction(db) { repository.hasAnyAdmin() })

        save((read(user.id) ?: fail()).setPanelRoles(listOf(ADMIN), by = admin).getOrNull() ?: fail())
        assertTrue(transaction(db) { repository.hasAnyAdmin() })
    }
}
