package com.kiva.domain.aggregate.order.entity

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.OrderFixtures.address
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.messenger
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.placement
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.event.OrderPersonalDataErased
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Erasing an order's personal data when its customer deletes their account (roadmap phase 14, decision 2): Sara's golden
 * order, with a note and a gift message, paid.
 */
class OrderErasureTest {

    private val system = UserId.SYSTEM

    private fun paidOrder(): Order = placedOrder(
        placement(
            customerNote = CustomerNote.makeNew("لطفاً زنگ نزنید").getOrNull()!!,
            gift = GiftNote.makeNew("تولدت مبارک", hidePrices = true).getOrNull()!!,
        ),
    ).also { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), Money.constructFromPersisted(3_480_000), false, sara, at(5)).getOrNull()!! }

    @Nested
    inner class Erasing {
        @Test
        fun `the recipient, their phone, the line, plaque, unit and postal code go - the province and city stay`() {
            val order = paidOrder()

            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(60)).getOrNull()!!

            with(order.address) {
                assertEquals(listOf(address.provinceName, address.cityName), listOf(provinceName, cityName))
                assertEquals(listOf(null, null, null, null, null, null, null), listOf(addressId, line, plaque, unit, postalCode, recipientName, recipientPhone))
                assertTrue(isErased)
            }
            assertTrue(order.isErased)
        }

        @Test
        fun `the messenger keeps its channel only, and the note and the gift message go - hidden prices stay`() {
            val order = paidOrder()

            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(60)).getOrNull()!!

            assertEquals(messenger.channel, order.messenger.channel)
            assertEquals(listOf(null, null, null), listOf(order.messenger.phone, order.messenger.username, order.messenger.note))
            assertNull(order.customerNote)
            assertEquals(GiftNote.makeNew(null, hidePrices = true).getOrNull(), order.gift)
        }

        @Test
        fun `its money, lines, status and dates don't move`() {
            val order = paidOrder()
            val before = listOf(order.status, order.totalsView, order.lines, order.placedOn, order.paidOn, order.code)

            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(60)).getOrNull()!!

            assertEquals(before, listOf(order.status, order.totalsView, order.lines, order.placedOn, order.paidOn, order.code))
            assertEquals(OrderStatus.PROCESSING, order.status)
        }

        @Test
        fun `it is one event with its reason, by whoever deleted the account`() {
            val order = paidOrder()
            val recorded = order.getRecordedEvents().size

            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, sara, at(60)).getOrNull()!!

            val event = assertIs<OrderPersonalDataErased>(order.getRecordedEvents().last())
            assertEquals(recorded + 1, order.getRecordedEvents().size)
            assertEquals(ErasureReason.ACCOUNT_DELETED, event.reason)
            assertEquals(listOf(sara, at(60)), listOf(event.occurredByUserId, event.occurredOn))
        }

        @Test
        fun `erasing an erased order again records nothing`() {
            val order = paidOrder()
            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(60)).getOrNull()!!
            val recorded = order.getRecordedEvents().size

            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(61)).getOrNull()!!

            assertEquals(recorded, order.getRecordedEvents().size)
        }

        @Test
        fun `an unpaid order is erased the same way - whether it may be is the use case's question`() {
            val order = placedOrder()

            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(1)).getOrNull()!!

            assertTrue(order.isErased)
            assertEquals(OrderStatus.PENDING_PAYMENT, order.status)
        }
    }

    @Test
    fun `replaying the stream with its erasure gives the erased order again`() {
        val order = paidOrder()
        order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, system, at(60)).getOrNull()!!

        val replayed = Order(order.getRecordedEvents())

        assertEquals(order.address, replayed.address)
        assertEquals(order.messenger, replayed.messenger)
        assertEquals(listOf(order.customerNote, order.gift, order.status, order.totalsView), listOf(replayed.customerNote, replayed.gift, replayed.status, replayed.totalsView))
        assertFalse(order.getRecordedEvents().dropLast(1).any { it is OrderPersonalDataErased }, "the events before it are not rewritten")
    }

    @Nested
    inner class TheParts {
        @Test
        fun `an erased address is erased again to itself`() {
            assertEquals(address.erased(), address.erased().erased())
            assertFalse(address.isErased)
        }

        @Test
        fun `a stored address half erased is a bug - it stops`() {
            assertFailsWith<IllegalArgumentException> {
                AddressSnapshot.constructFromPersisted(null, "تهران", "تهران", address.line, null, null, null, null, null)
            }
            assertFailsWith<IllegalArgumentException> {
                AddressSnapshot.constructFromPersisted(address.addressId, "تهران", "تهران", null, null, null, null, null, null)
            }
        }

        @Test
        fun `an erased messenger keeps no texts - a stored one with a handle and no phone is a bug`() {
            assertTrue(messenger.erased().isErased)
            assertFailsWith<IllegalArgumentException> { PreShipmentMessenger.constructFromPersisted(MessengerChannel.BALE, null, "@sara_m", null) }
        }

        @Test
        fun `an erased address or messenger prints nothing of what was there`() {
            assertEquals("AddressSnapshot(addressId=null)", address.erased().toString())
            assertEquals("PreShipmentMessenger(channel=${messenger.channel})", messenger.erased().toString())
        }
    }
}
