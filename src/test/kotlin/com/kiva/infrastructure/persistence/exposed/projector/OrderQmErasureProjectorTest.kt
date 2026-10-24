package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.repository.OrderQm
import com.kiva.common.text.normalizePersian
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredOrders
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * orderqm_tbl and OrderPersonalDataErased (roadmap phase 14, decision 2), on kiva_test: the row blanks what the order
 * blanks, is found by its code and city only, gets no timeline entry, and agrees with the order read back from its
 * events.
 */
class OrderQmErasureProjectorTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val readModel = OrderQmRepositoryImpl()
    private val now = orders.now

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))

    private fun qmOf(order: Order): OrderQm = transaction(db) { readModel.findByCode(order.code) }!!

    private fun searchTextOf(order: Order): String =
        transaction(db) { OrderQmTbl.select(OrderQmTbl.searchText).where { OrderQmTbl.orderId eq order.id.value }.single()[OrderQmTbl.searchText] }

    /** A paid order with a note and a gift message, erased. */
    private fun erasedOrder(): Order {
        val order = orders.place(
            listOf(orders.line(1, orders.newBag())),
            customerNote = CustomerNote.makeNew("لطفاً زنگ نزنید").getOrNull()!!,
            gift = GiftNote.makeNew("تولدت مبارک", hidePrices = true).getOrNull()!!,
        )
        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
        orders.change(order.id) { it.erasePersonalData(ErasureReason.ACCOUNT_DELETED, order.userId, at(60)).getOrNull()!! }
        return order
    }

    @Test
    fun `the row keeps the province and city, the messenger's channel and hidden prices - the rest of what is personal is gone`() {
        val qm = qmOf(erasedOrder())

        with(qm.address) {
            assertEquals(listOf("تهران", "تهران"), listOf(provinceName, cityName))
            assertEquals(listOf(null, null, null, null, null, null, null), listOf(addressId, line, plaque, unit, postalCode, recipientName, recipientPhone))
        }
        assertEquals(MessengerChannel.TELEGRAM, qm.messenger.channel)
        assertEquals(listOf(null, null, null), listOf(qm.messenger.phone, qm.messenger.username, qm.messenger.note))
        assertNull(qm.customerNote)
        assertEquals(GiftNote.makeNew(null, hidePrices = true).getOrNull(), qm.gift)
    }

    @Test
    fun `the money, the lines, the status and the timeline stay - the erasure adds no entry`() {
        val order = orders.place(listOf(orders.line(1, orders.newBag())))
        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
        val before = qmOf(order)

        orders.change(order.id) { it.erasePersonalData(ErasureReason.ACCOUNT_DELETED, order.userId, at(60)).getOrNull()!! }

        val after = qmOf(order)
        assertEquals(OrderStatus.PROCESSING, after.status)
        assertEquals(listOf(before.totals, before.lines, before.timeline, before.paidOn), listOf(after.totals, after.lines, after.timeline, after.paidOn))
    }

    @Test
    fun `the search text is made again of the code and the city only - no name, no phone`() {
        val order = erasedOrder()

        val text = searchTextOf(order)

        assertEquals("${order.code.value} تهران".normalizePersian(), text)
        assertFalse("0912" in text || "سارا" in text, text)
    }

    @Test
    fun `the row and the order read back from its events say the same`() {
        val order = erasedOrder()

        val replayed = orders.read(order.id)!!
        val qm = qmOf(order)

        assertTrue(replayed.isErased)
        assertEquals(replayed.address, qm.address)
        assertEquals(replayed.messenger, qm.messenger)
        assertEquals(listOf(replayed.customerNote, replayed.gift), listOf(qm.customerNote, qm.gift))
    }

    @Test
    fun `an order that is not erased is still found by its recipient`() {
        val order = orders.place(listOf(orders.line(1, orders.newBag())))

        val text = searchTextOf(order)

        assertTrue("09121234567" in text && "سارا".normalizePersian() in text, text)
    }
}
