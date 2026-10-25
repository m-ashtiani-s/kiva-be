package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderTimelineKind
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.policy.OrderActionsPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredOrders
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * orderqm_tbl and a return (phase 7 step 7.2, roadmap section 12.5), on kiva_test, each step in its own transaction as in
 * the app: the order's status follows its open return, «مرجوع کردن» goes off for good once one is closed or refunded,
 * and no step touches what was sold.
 */
class OrderQmReturnProjectorTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val readModel = OrderQmRepositoryImpl()
    private val now = orders.now
    private val operator = UserId.constructFromPersisted(900)
    private val rt = ReturnId.constructFromPersisted(12)

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))

    private fun qmOf(order: Order): OrderQm = transaction(db) { readModel.findByCode(order.code) }!!

    private val deliveredAt = at(60 * 30)

    private fun delivered(bag: Product): Order {
        val order = orders.place(listOf(orders.line(1, bag, color = 0, quantity = 2)))
        val photo = OrderPhoto(MediaId.constructFromPersisted("med_" + "1".padStart(22, '0')), 1, MediaView.FRONT, 0)
        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
        orders.change(order.id) { it.attachPhotos(listOf(photo), operator, at(10)).getOrNull()!! }
        orders.change(order.id) { it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(20)).getOrNull()!! }
        orders.change(order.id) { it.approvePhotos(false, order.userId, at(30)).getOrNull()!! }
        orders.change(order.id) { it.ship(ShippingMethodCode.POST, TrackingCode.constructFromPersisted("TPX4821760"), at(40), true, operator, at(40)).getOrNull()!! }
        orders.change(order.id) { it.markDelivered(7, null, operator, deliveredAt).getOrNull()!! }
        return order
    }

    private fun requestReturn(order: Order) =
        orders.change(order.id) { it.requestReturn(rt, listOf(ReturnItem.constructFromPersisted(1, 1)), order.userId, deliveredAt.plusSeconds(60)).getOrNull()!! }

    private fun canReturn(qm: OrderQm) = OrderActionsPolicy.actionsOf(qm.actionFactsAt(deliveredAt.plusSeconds(600))).canReturn

    @Test
    fun `asked - RETURN_REQUESTED with the return open and its entry - arrived - RETURNED - refunded - REFUNDED, settled, nothing open`() {
        val bag = orders.newBag()
        val order = delivered(bag)
        val sold = orders.soldCountOf(bag)

        requestReturn(order)
        val asked = qmOf(order)
        orders.change(order.id) { it.receiveReturn(rt, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }
        val arrived = qmOf(order)
        orders.change(order.id) { it.endReturn(rt, ReturnOutcome.REFUNDED, operator, deliveredAt.plusSeconds(180)).getOrNull()!! }
        val refunded = qmOf(order)

        assertEquals(listOf(OrderStatus.RETURN_REQUESTED, rt, false), listOf(asked.status, asked.openReturnId, asked.returnSettled))
        assertEquals(listOf(OrderStatus.RETURNED, rt), listOf(arrived.status, arrived.openReturnId))
        assertEquals(listOf(OrderStatus.REFUNDED, null, true), listOf(refunded.status, refunded.openReturnId, refunded.returnSettled))
        assertEquals(
            listOf(OrderTimelineKind.RETURN_REQUESTED, OrderTimelineKind.RETURN_RECEIVED, OrderTimelineKind.RETURN_REFUNDED),
            refunded.timeline.map { it.kind }.takeLast(3),
        )
        assertEquals(listOf(OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED, OrderStatus.REFUNDED), refunded.timeline.map { it.status }.takeLast(3))
        assertEquals(sold, orders.soldCountOf(bag), "a returned bag was still bought")
    }

    @Test
    fun `turned down - DELIVERED again, nothing settled, the button back - closed - DELIVERED, settled, the button gone for good`() {
        val rejected = delivered(orders.newBag())
        requestReturn(rejected)
        orders.change(rejected.id) { it.endReturn(rt, ReturnOutcome.REJECTED, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }
        val closed = delivered(orders.newBag())
        requestReturn(closed)
        orders.change(closed.id) { it.endReturn(rt, ReturnOutcome.CLOSED, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }

        val rejectedQm = qmOf(rejected)
        val closedQm = qmOf(closed)

        assertEquals(listOf(OrderStatus.DELIVERED, null, false), listOf(rejectedQm.status, rejectedQm.openReturnId, rejectedQm.returnSettled))
        assertEquals(OrderTimelineKind.RETURN_REJECTED, rejectedQm.timeline.last().kind)
        assertTrue(canReturn(rejectedQm))
        assertEquals(listOf(OrderStatus.DELIVERED, null, true), listOf(closedQm.status, closedQm.openReturnId, closedQm.returnSettled))
        assertEquals(OrderTimelineKind.RETURN_CLOSED, closedQm.timeline.last().kind)
        assertFalse(canReturn(closedQm))
    }

    @Test
    fun `a delivered order shows the button, one with a return open doesn't`() {
        val order = delivered(orders.newBag())

        assertTrue(canReturn(qmOf(order)))
        assertNull(qmOf(order).openReturnId)
        requestReturn(order)
        assertFalse(canReturn(qmOf(order)))
    }

    @Test
    fun `the read model refuses an open return on an order that isn't RETURN_REQUESTED or RETURNED, and RETURNED without one`() {
        val order = delivered(orders.newBag())

        assertFailsWith<ExposedSQLException> {
            transaction(db) { exec("""UPDATE orderqm_tbl SET "openReturnId" = 12 WHERE "orderId" = ${order.id.value}""") }
        }
        assertFailsWith<ExposedSQLException> {
            transaction(db) { exec("""UPDATE orderqm_tbl SET status = 'RETURNED' WHERE "orderId" = ${order.id.value}""") }
        }
    }
}
