package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.projector.ProductQmSummary
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orders stored as events in kiva_test and read back, each step in its own transaction as in the app. Their bags are
 * real products of the read model, because a payment counts them as sold. Everything is deleted after each test.
 */
class OrderRepositoryImplTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()

    private val firstAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000A1")
    private val secondAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000B2")

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun Order.state(): List<Any?> = listOf(
        id, code, userId, status, lines, address, messenger, shippingMethod, totals, reserveRequested, placedOn, holdUntil, paidOn, paidByPaymentId,
        customerNote, gift,
    )

    private fun streamLength(id: OrderId): Long = transaction(db) {
        EventStoreTbl.selectAll()
            .where { (EventStoreTbl.aggregateName eq "ORDER") and (EventStoreTbl.aggregateId eq id.value.toString()) }
            .count()
    }

    private fun storedOrder(): Order {
        val bag = orders.newBag()
        return orders.place(
            listOf(orders.line(1, bag, color = 0, quantity = 2), orders.line(2, bag, color = 1)),
            customerNote = CustomerNote.constructFromPersisted("لطفاً زنگ بزنید"),
            gift = GiftNote.constructFromPersisted("تولدت مبارک!", hidePrices = true),
        )
    }

    @Test
    fun `a placed order, a failed attempt and a payment read back exactly the same`() {
        val placed = storedOrder()
        orders.change(placed.id) { it.recordPaymentFailure(firstAttempt, PaymentFailureReason.USER_CANCELLED, UserId.SYSTEM, orders.now.plusSeconds(60)) }
        val paid = orders.change(placed.id) { it.markPaid(secondAttempt, it.totals.payable, false, UserId.SYSTEM, orders.now.plusSeconds(120)) }

        val loaded = orders.read(placed.id)!!

        assertEquals(paid.state(), loaded.state())
        assertEquals(OrderStatus.PROCESSING, loaded.status)
        assertEquals(3, streamLength(placed.id))
        assertEquals(emptyList(), loaded.getRecordedEvents())
    }

    @Test
    fun `saving again adds only the new events`() {
        val placed = storedOrder()
        assertEquals(1, streamLength(placed.id))

        orders.change(placed.id) { it.recordPaymentFailure(firstAttempt, PaymentFailureReason.USER_CANCELLED, UserId.SYSTEM, orders.now) }
        orders.change(placed.id) { it.recordPaymentFailure(firstAttempt, PaymentFailureReason.USER_CANCELLED, UserId.SYSTEM, orders.now) }

        assertEquals(2, streamLength(placed.id))
    }

    @Test
    fun `an order nothing was stored for is null`() {
        val id = transaction(db) { orders.ids.nextOrderId() }

        assertNull(orders.read(id))
    }

    @Test
    fun `two saves of one order at once - the second is refused, and nothing of it stays, not in the read model either`() {
        val placed = storedOrder()
        val callback = orders.read(placed.id)!!
        val job = orders.read(placed.id)!!

        callback.markPaid(firstAttempt, callback.totals.payable, false, UserId.SYSTEM, orders.now.plusSeconds(60))
        job.expire(UserId.SYSTEM, placed.holdUntil)
        transaction(db) { orders.repository.save(callback) }

        assertFailsWith<ConcurrentChangeException> { transaction(db) { orders.repository.save(job) } }
        assertEquals(OrderStatus.PROCESSING, orders.read(placed.id)?.status)
        assertEquals(2, streamLength(placed.id))
        val row = transaction(db) { OrderQmTbl.selectAll().where { OrderQmTbl.orderId eq placed.id.value }.single() }
        assertEquals(OrderStatus.PROCESSING, row[OrderQmTbl.status])
        assertEquals(listOf("PENDING_PAYMENT", "PROCESSING"), row[OrderQmTbl.timeline].map { it.status })
    }

    /**
     * The expiry job gives the stock back first (it locks the products' rows), then saves the order; a callback paying
     * the same order locks the same rows to count them as sold. Both take the products' rows before the order's
     * stream, so the second waits for the first and is then refused as a change made meanwhile, never a deadlock.
     */
    @Test
    fun `a payment and an expiry of one order at the same moment end with one of them, never a deadlock`() {
        val placed = storedOrder()
        val paying = orders.read(placed.id)!!
        val expiring = orders.read(placed.id)!!
        val productsLocked = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val expiry = pool.submit<Result<Unit>> {
            runCatching {
                transaction(db) {
                    // As releasing the stock would: the products' rows, ascending, then a moment of other work.
                    placed.lines.map { it.productId.value }.distinct().sorted().forEach(ProductQmSummary::lock)
                    productsLocked.countDown()
                    Thread.sleep(300)
                    expiring.expire(UserId.SYSTEM, placed.holdUntil)
                    orders.repository.save(expiring)
                }
            }
        }
        val payment = pool.submit<Result<Unit>> {
            productsLocked.await()
            runCatching {
                transaction(db) {
                    paying.markPaid(firstAttempt, paying.totals.payable, false, UserId.SYSTEM, orders.now.plusSeconds(60))
                    orders.repository.save(paying)
                }
            }
        }
        val results = listOf(expiry, payment).map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results[0].isSuccess, "${results[0]}")
        assertIs<ConcurrentChangeException>(results[1].exceptionOrNull(), "${results[1]}")
        assertEquals(OrderStatus.EXPIRED, orders.read(placed.id)?.status)
    }

    @Test
    fun `order ids come from the sequence, each once and in order`() {
        val ids = transaction(db) { List(20) { orders.ids.nextOrderId().value } }

        assertEquals(ids.sorted(), ids)
        assertEquals(20, ids.toSet().size)
    }
}
