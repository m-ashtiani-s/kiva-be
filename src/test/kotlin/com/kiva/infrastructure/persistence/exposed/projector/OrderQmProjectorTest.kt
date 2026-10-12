package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.repository.OrderQm
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredOrders
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * orderqm_tbl kept in step with the orders' events on kiva_test, each step in its own transaction as in the app, and
 * read back through OrderQmRepositoryImpl. The bags are real products of the read model. Everything is deleted after
 * each test.
 */
class OrderQmProjectorTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val readModel = OrderQmRepositoryImpl()
    private val now = orders.now

    private val firstAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000A1")
    private val secondAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000B2")

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun qmOf(order: Order, userId: UserId = order.userId): OrderQm? = transaction(db) { readModel.findOfCustomer(userId, order.code) }

    private fun timelineOf(order: Order): List<Triple<OrderStatus, Long, PaymentFailureReason?>> =
        qmOf(order)!!.timeline.map { Triple(it.status, Duration.between(now, it.at).seconds, it.failureReason) }

    private fun pay(order: Order, after: Long = 120) =
        orders.change(order.id) { it.markPaid(secondAttempt, it.totals.payable, false, UserId.SYSTEM, now.plusSeconds(after)) }

    @Test
    fun `a placed order is one row with its lines, address, totals and a first timeline entry`() {
        val bag = orders.newBag()
        val order = orders.place(
            listOf(orders.line(1, bag, color = 0, quantity = 2), orders.line(2, bag, color = 1)),
            customerNote = CustomerNote.constructFromPersisted("لطفاً زنگ بزنید"),
            gift = GiftNote.constructFromPersisted("تولدت مبارک!", hidePrices = true),
        )

        val qm = qmOf(order)!!

        assertEquals(
            listOf(order.id, order.code, order.userId, OrderStatus.PENDING_PAYMENT, now, null, now.plusSeconds(900), order.shippingMethod, false),
            listOf(qm.orderId, qm.code, qm.userId, qm.status, qm.placedOn, qm.paidOn, qm.holdUntil, qm.shippingMethod, qm.reserveRequested),
        )
        assertEquals(order.lines, qm.lines)
        assertEquals(order.address, qm.address)
        assertEquals(order.messenger, qm.messenger)
        assertEquals(order.totals, qm.totals)
        assertEquals(order.customerNote, qm.customerNote)
        assertEquals(order.gift, qm.gift)
        assertEquals(listOf(Triple(OrderStatus.PENDING_PAYMENT, 0L, null)), timelineOf(order))

        val row = transaction(db) { OrderQmTbl.selectAll().where { OrderQmTbl.orderId eq order.id.value }.single() }
        assertEquals(4_350_000L, row[OrderQmTbl.payable])
        assertEquals(3, row[OrderQmTbl.itemsCount])
    }

    @Test
    fun `every event adds its timeline entry - each failed attempt with its reason, then the payment`() {
        val order = orders.place(listOf(orders.line(1, orders.newBag())))

        orders.change(order.id) { it.recordPaymentFailure(firstAttempt, PaymentFailureReason.USER_CANCELLED, UserId.SYSTEM, now.plusSeconds(60)) }
        orders.change(order.id) { it.recordPaymentFailure(PaymentPublicId.constructFromPersisted("pay_0000000000C3"), PaymentFailureReason.GATEWAY_DECLINED, UserId.SYSTEM, now.plusSeconds(90)) }
        pay(order, after = 120)

        assertEquals(
            listOf(
                Triple(OrderStatus.PENDING_PAYMENT, 0L, null),
                Triple(OrderStatus.PAYMENT_FAILED, 60L, PaymentFailureReason.USER_CANCELLED),
                Triple(OrderStatus.PAYMENT_FAILED, 90L, PaymentFailureReason.GATEWAY_DECLINED),
                Triple(OrderStatus.PROCESSING, 120L, null),
            ),
            timelineOf(order),
        )
        val qm = qmOf(order)!!
        assertEquals(OrderStatus.PROCESSING, qm.status)
        assertEquals(now.plusSeconds(120), qm.paidOn)
    }

    @Test
    fun `an event changes only its own order's row`() {
        val bag = orders.newBag()
        val paid = orders.place(listOf(orders.line(1, bag)))
        val other = orders.place(listOf(orders.line(1, bag)))
        val before = transaction(db) { OrderQmTbl.selectAll().where { OrderQmTbl.orderId eq other.id.value }.single().let { row -> OrderQmTbl.columns.map { row[it] } } }

        pay(paid)
        orders.change(paid.id) { it.expire(UserId.SYSTEM, it.holdUntil) } // refused: paid. Nothing is stored.

        val after = transaction(db) { OrderQmTbl.selectAll().where { OrderQmTbl.orderId eq other.id.value }.single().let { row -> OrderQmTbl.columns.map { row[it] } } }
        assertEquals(before, after)
        assertEquals(OrderStatus.PROCESSING, qmOf(paid)?.status)
    }

    @Test
    fun `a payment counts the bags as sold - each product by its quantity, all its colors together`() {
        val aida = orders.newBag()
        val nila = orders.newBag()
        val order = orders.place(listOf(orders.line(1, aida, color = 0, quantity = 2), orders.line(2, nila), orders.line(3, aida, color = 1)))
        val untouched = orders.newBag()

        assertEquals(listOf(0, 0), listOf(orders.soldCountOf(aida), orders.soldCountOf(nila)))
        orders.change(order.id) { it.recordPaymentFailure(firstAttempt, PaymentFailureReason.USER_CANCELLED, UserId.SYSTEM, now.plusSeconds(30)) }
        assertEquals(0, orders.soldCountOf(aida))

        pay(order)

        assertEquals(listOf(3, 1, 0), listOf(orders.soldCountOf(aida), orders.soldCountOf(nila), orders.soldCountOf(untouched)))
    }

    @Test
    fun `an order with nothing to pay is paid as it is placed - two entries, PROCESSING and sold`() {
        val bag = orders.newBag()
        val free = OrderTotals.constructFromPersisted(1, 1, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, true, ShippingFreeReason.THRESHOLD, Money.ZERO)

        val order = orders.place(listOf(orders.line(1, bag, price = 0, compareAt = null)), totals = free)

        assertEquals(listOf(Triple(OrderStatus.PENDING_PAYMENT, 0L, null), Triple(OrderStatus.PROCESSING, 0L, null)), timelineOf(order))
        assertEquals(now, qmOf(order)?.paidOn)
        assertEquals(1, orders.soldCountOf(bag))
    }

    @Test
    fun `an expired order is EXPIRED, and nothing is counted as sold`() {
        val bag = orders.newBag()
        val order = orders.place(listOf(orders.line(1, bag)))

        orders.change(order.id) { it.expire(UserId.SYSTEM, it.holdUntil) }

        assertEquals(OrderStatus.EXPIRED, qmOf(order)?.status)
        assertEquals(listOf(Triple(OrderStatus.PENDING_PAYMENT, 0L, null), Triple(OrderStatus.EXPIRED, 900L, null)), timelineOf(order))
        assertEquals(0, orders.soldCountOf(bag))
    }

    @Test
    fun `another customer's order, or a code nobody has, is not found`() {
        val order = orders.place(listOf(orders.line(1, orders.newBag())))

        assertNull(qmOf(order, userId = UserId.constructFromPersisted(8)))
        assertNull(transaction(db) { readModel.findOfCustomer(order.userId, OrderCode.constructFromPersisted("KV-1000")) })
    }

    // ============ the database's own walls ============

    private fun insertRow(code: String = "KV-555555", status: String = "PENDING_PAYMENT", paidOn: String = "NULL", timeline: String = """[{"status":"PENDING_PAYMENT"}]""") =
        transaction(db) {
            exec(
                """
                INSERT INTO orderqm_tbl ("orderId", code, "userId", status, "placedOn", "paidOn", "holdUntil", payable, "itemsCount",
                    "shippingMethod", "messengerChannel", "reserveRequested", lines, address, messenger, totals, timeline, "updatedOn")
                VALUES (987654321, '$code', 7, '$status', now(), $paidOn, now() + interval '15 minutes', 1450000, 1,
                    'POST', 'TELEGRAM', false, '[{}]', '{}', '{}', '{}', '$timeline', now())
                """.trimIndent(),
            )
        }

    @Test
    fun `the read model refuses a paid order without its time of payment, an unpaid one with it, a bad code and an empty timeline`() {
        assertFailsWith<ExposedSQLException> { insertRow(status = "PROCESSING") }
        assertFailsWith<ExposedSQLException> { insertRow(paidOn = "now()") }
        assertFailsWith<ExposedSQLException> { insertRow(code = "KV-12") }
        assertFailsWith<ExposedSQLException> { insertRow(timeline = "[]") }
        insertRow()
        transaction(db) { exec("""DELETE FROM orderqm_tbl WHERE "orderId" = 987654321""") }
    }
}
