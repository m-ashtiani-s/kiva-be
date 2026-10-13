package com.kiva.domain.aggregate.reservation.entity

import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.reservation.ReservationFixtures.address
import com.kiva.domain.aggregate.reservation.ReservationFixtures.group
import com.kiva.domain.aggregate.reservation.model.valueobject.AddressFingerprint
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus.HOLDING
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus.RELEASED
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReservationGroupTest {

    private val fourDays = Duration.ofDays(4)

    @Test
    fun `a group starts when its first order is paid and holds for 4 days - SG and its sequence number`() {
        val group = group()

        assertEquals("SG-1001", group.code.value)
        assertEquals(now, group.startedOn)
        assertEquals(now.plus(fourDays), group.expiresOn)
        assertEquals(HOLDING, group.status)
        assertNull(group.releasedOn)
    }

    @Test
    fun `it holds until the second its time is over, and never once let go`() {
        val group = group()

        assertTrue(group.isHolding(now.plus(fourDays).minusSeconds(1)))
        assertFalse(group.isHolding(now.plus(fourDays)))
        group.release(now.plus(fourDays))
        assertFalse(group.isHolding(now))
    }

    @Test
    fun `it takes an order to its very address - the same saved one, unedited - while it holds`() {
        val group = group()
        val inTime = now.plus(Duration.ofDays(1))

        assertTrue(group.takes(address(), inTime))
        assertFalse(group.takes(address(addressId = 43), inTime), "another saved address")
        assertFalse(group.takes(address(recipientName = "مریم محمدی"), inTime), "the address edited since")
        assertFalse(group.takes(address(line = "خیابان ولیعصر، کوچه نسترن"), inTime), "the address edited since")
        assertFalse(group.takes(address(addressId = null), inTime), "an address not in the book")
        assertFalse(group.takes(address(), now.plus(fourDays)), "its time is over")
    }

    @Test
    fun `a group started at an address not kept in the book takes nothing`() {
        val group = group(address = address(addressId = null))

        assertFalse(group.takes(address(addressId = null), now))
        assertFalse(group.takes(address(), now))
    }

    @Test
    fun `letting go is once - the second time changes nothing`() {
        val group = group()
        val due = now.plus(fourDays)

        assertTrue(group.release(due))
        assertFalse(group.release(due.plusSeconds(60)))
        assertEquals(RELEASED, group.status)
        assertEquals(due, group.releasedOn)
    }

    @Test
    fun `the fingerprint is the same for the same address and changes with any part a courier reads`() {
        assertEquals(AddressFingerprint.of(address()), AddressFingerprint.of(address()))
        assertEquals(AddressFingerprint.of(address(addressId = 42)), AddressFingerprint.of(address(addressId = 43)), "the id is compared on its own")
        assertNotEquals(AddressFingerprint.of(address()), AddressFingerprint.of(address(recipientName = "سارا محمدیان")))
        assertEquals(AddressFingerprint.LENGTH, AddressFingerprint.of(address()).toByteArray().size)
    }
}
