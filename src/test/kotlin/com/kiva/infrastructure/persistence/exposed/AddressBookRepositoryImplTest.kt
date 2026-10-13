package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.AddressTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The address book's table on kiva_test: V13's rules, and the repository writing exactly what changed. Each test
 * registers its own customer; deleting the customer afterwards takes their addresses with them (ON DELETE CASCADE).
 */
class AddressBookRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = AddressBookRepositoryImpl()
    private val ids = AddressIdGeneratorImpl()
    private val now = Instant.parse("2036-06-01T09:00:00Z")

    private val user: UserId = transaction(db) {
        UserRepositoryImpl().findOrRegister(IranianMobileNumber.constructFromPersisted("09909100001"), now).user.id
    }

    @AfterEach
    fun deleteTheCustomer() {
        transaction(db) { UserTbl.deleteWhere { phone like "0990910%" } }
    }

    private fun input(provinceId: Int = 8, cityId: Int = 394, plaque: String? = "12", lat: Double? = 35.7575) = AddressInput.makeNew(
        title = "خانه", provinceId = provinceId, cityId = cityId, addressLine = "خیابان ولیعصر، کوچه نسترن، پلاک ۱۲", plaque = plaque, unit = "4",
        postalCode = "1965843117", recipientName = "سارا محمدی", recipientPhone = "09123456789", isSelfRecipient = true,
        lat = lat, lng = lat?.let { 51.4107 },
    ).getOrNull()!!

    /** A book with [n] addresses, a minute apart, saved; the first is the default. */
    private fun savedBook(n: Int): AddressBook = transaction(db) {
        val book = repository.lockAndFind(user)
        repeat(n) { book.add(ids.nextAddressId(), input(), setAsDefault = false, now.plus(it.toLong(), ChronoUnit.MINUTES)) }
        repository.save(book)
        repository.find(user)
    }

    private fun change(block: (AddressBook) -> Unit): AddressBook = transaction(db) {
        val book = repository.lockAndFind(user)
        block(book)
        repository.save(book)
        repository.find(user)
    }

    private fun AddressBook.defaults() = addresses.filter { it.isDefault }.map { it.id }

    private fun Address.values(): List<Any?> = listOf(
        id, title, provinceId, cityId, line, plaque, unit, postalCode, recipientName, recipientPhone, isSelfRecipient, location, isDefault,
        createdOn, updatedOn,
    )

    @Test
    fun `an empty book can be locked, and a saved address reads back with every value`() {
        val book = transaction(db) { repository.lockAndFind(user) }
        assertEquals(emptyList(), book.addresses)

        val added = transaction(db) {
            val locked = repository.lockAndFind(user)
            val address = locked.add(ids.nextAddressId(), input(), setAsDefault = false, now).getOrNull()!!
            repository.save(locked)
            address
        }

        val read = transaction(db) { repository.find(user) }.addresses.single()
        assertEquals(added.values(), read.values())
    }

    @Test
    fun `an edit, a move of the default and a removal are written, and nothing else`() {
        val (first, second, third) = savedBook(3).addresses.map { it.id }

        val book = change {
            it.update(second, input(provinceId = 4, cityId = 1080, plaque = null, lat = null), setAsDefault = true, now.plusSeconds(3600))
            it.remove(third, now.plusSeconds(3600))
        }

        assertEquals(listOf(first, second), book.addresses.map { it.id })
        assertEquals(listOf(second), book.defaults())
        val edited = book.find(second)!!
        assertEquals(listOf(4, 1080, null, null), listOf(edited.provinceId.value, edited.cityId.value, edited.plaque, edited.location))
        assertEquals(now, book.find(first)!!.createdOn)
    }

    @Test
    fun `removing the default writes the new default without a moment of two`() {
        val (first, second) = savedBook(3).addresses.map { it.id }

        val book = change { it.remove(first, now.plusSeconds(3600)) }

        assertEquals(listOf(second), book.defaults())
    }

    @Test
    fun `the database refuses a second default for one customer`() {
        savedBook(2)

        transaction(db) {
            assertFailsWith<ExposedSQLException> { exec("""UPDATE address_tbl SET "isDefault" = TRUE WHERE "userId" = ${user.value}""") }
            rollback()
        }
    }

    @Test
    fun `the database refuses a city of another province, a broken postal code and half a point`() {
        val id = savedBook(1).addresses.single().id.value

        for (change in listOf(
            """"provinceId" = 4""",
            """"postalCode" = '19658-4311'""",
            """lng = NULL""",
            """"addressLine" = 'کوتاه'""",
            """"recipientPhone" = '9123456789'""",
        )) {
            transaction(db) {
                assertFailsWith<ExposedSQLException>(change) { exec("""UPDATE address_tbl SET $change WHERE "addressId" = $id""") }
                rollback()
            }
        }
    }

    @Test
    fun `address numbers come from their own sequence, one after the other`() {
        val (a, b) = transaction(db) { listOf(ids.nextAddressId(), ids.nextAddressId()) }

        assertEquals(a.value + 1, b.value)
    }

    @Test
    fun `deleting the customer deletes their addresses`() {
        savedBook(2)

        transaction(db) { UserTbl.deleteWhere { UserTbl.id eq user.value } }

        val left = transaction(db) { AddressTbl.selectAll().where { AddressTbl.userId eq user.value }.count() }
        assertEquals(0, left)
        assertTrue(transaction(db) { repository.find(user).addresses.isEmpty() })
    }

    @Test
    fun `ids that look alike never mix two books`() {
        savedBook(1)

        assertEquals(emptyList(), transaction(db) { repository.find(UserId.constructFromPersisted(user.value + 1_000_000)).addresses })
        assertEquals(null, transaction(db) { repository.find(user).find(AddressId.constructFromPersisted(Long.MAX_VALUE)) })
    }
}
