package com.kiva.domain.aggregate.address.entity

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.addressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddressBookTest {

    private val sara = UserId.constructFromPersisted(7)
    private val start = Instant.parse("2026-10-11T08:00:00Z")
    private fun at(minute: Long): Instant = start.plusSeconds(minute * 60)
    private fun id(n: Long) = AddressId.constructFromPersisted(n)

    /** A book with addresses 1..n added a minute apart; the first is the default. */
    private fun bookWith(n: Int): AddressBook = AddressBook.empty(sara).apply {
        (1..n).forEach { add(id(it.toLong()), addressInput(), setAsDefault = false, at(it.toLong())).getOrNull()!! }
    }

    private fun AddressBook.defaults(): List<Long> = addresses.filter { it.isDefault }.map { it.id.value }

    @Test
    fun `the first address is the default even when not asked for`() {
        val book = AddressBook.empty(sara)

        val first = book.add(id(1), addressInput(), setAsDefault = false, at(1)).getOrNull()!!

        assertTrue(first.isDefault)
        assertEquals(at(1), first.createdOn)
    }

    @Test
    fun `a later address becomes the default only when asked, and the old one stops being it`() {
        val book = bookWith(1)

        book.add(id(2), addressInput(), setAsDefault = false, at(2))
        assertEquals(listOf(1L), book.defaults())

        book.add(id(3), addressInput(), setAsDefault = true, at(3))
        assertEquals(listOf(3L), book.defaults())
    }

    @Test
    fun `editing with setAsDefault false never takes the default away, true moves it`() {
        val book = bookWith(2)

        val edited = book.update(id(1), addressInput(addressLine = "خیابان انقلاب، کوچه بهار، پلاک ۳"), setAsDefault = false, at(10)).getOrNull()!!
        assertEquals(listOf(1L), book.defaults())
        assertEquals("خیابان انقلاب، کوچه بهار، پلاک ۳", edited.line.value)
        assertEquals(at(1) to at(10), edited.createdOn to edited.updatedOn)

        book.update(id(2), addressInput(), setAsDefault = true, at(11))
        assertEquals(listOf(2L), book.defaults())
    }

    @Test
    fun `removing the default makes the oldest one left the default, removing the last leaves an empty book`() {
        val book = bookWith(3)
        book.makeDefault(id(2), at(5))

        book.remove(id(2), at(6))
        assertEquals(listOf(1L), book.defaults())

        book.remove(id(1), at(7))
        assertEquals(listOf(3L), book.defaults())

        book.remove(id(3), at(8))
        assertEquals(emptyList(), book.addresses)
    }

    @Test
    fun `removing an address that isn't the default leaves the default where it is`() {
        val book = bookWith(3)

        book.remove(id(3), at(5))

        assertEquals(listOf(1L), book.defaults())
        assertEquals(listOf(1L, 2L), book.addresses.map { it.id.value })
    }

    @Test
    fun `the twenty-first address is refused, and nothing is added`() {
        val book = bookWith(20)

        val result = book.add(id(21), addressInput(), setAsDefault = true, at(30))

        assertEquals(Result.failure(AddressBook.Failure.LimitReached), result)
        assertEquals(20, book.addresses.size)
        assertEquals(listOf(1L), book.defaults())
        assertEquals("ADDRESS_LIMIT_REACHED", AddressBook.Failure.LimitReached.code)
    }

    @Test
    fun `an address not in the book is ADDRESS_NOT_FOUND for every change`() {
        val book = bookWith(1)
        val stranger = id(99)

        assertEquals(Result.failure(AddressBook.Failure.AddressNotFound), book.update(stranger, addressInput(), false, at(5)))
        assertEquals(Result.failure(AddressBook.Failure.AddressNotFound), book.remove(stranger, at(5)))
        assertEquals(Result.failure(AddressBook.Failure.AddressNotFound), book.makeDefault(stranger, at(5)))
        assertEquals(1, book.addresses.size)
    }

    @Test
    fun `making the default the default again changes nothing`() {
        val book = bookWith(2)
        val before = book.find(id(1))!!

        book.makeDefault(id(1), at(9))

        assertEquals(before.updatedOn, book.find(id(1))!!.updatedOn)
    }

    @Test
    fun `addresses are kept oldest first, and a stored book without exactly one default is a bug`() {
        val book = bookWith(3)
        val shuffled = AddressBook.constructFromPersisted(sara, book.addresses.reversed())

        assertEquals(listOf(1L, 2L, 3L), shuffled.addresses.map { it.id.value })
        assertFailsWith<IllegalArgumentException> {
            AddressBook.constructFromPersisted(sara, book.addresses.map { if (it.isDefault) it.withDefault(false, at(9)) else it })
        }
    }
}
