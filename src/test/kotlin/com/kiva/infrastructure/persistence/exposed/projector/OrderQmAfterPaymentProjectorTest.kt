package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderTimelineKind
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredOrders
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * orderqm_tbl after the payment (phase 2 step 2.2), kept in step with the orders' events on kiva_test, each change in its
 * own transaction as in the app. The bags are real products of the read model, because a payment counts them as sold and
 * a cancellation takes that back.
 */
class OrderQmAfterPaymentProjectorTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val readModel = OrderQmRepositoryImpl()
    private val now = orders.now
    private val operator = UserId.constructFromPersisted(900)
    private val tracking = TrackingCode.constructFromPersisted("TPX4821760")

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))

    private fun qmOf(order: Order): OrderQm = transaction(db) { readModel.findByCode(order.code) }!!

    private fun photo(n: Int, lineId: Int?) = OrderPhoto(MediaId.constructFromPersisted("med_" + n.toString().padStart(22, '0')), lineId, MediaView.FRONT, n)

    private fun paidOrder(bag: Product = orders.newBag(), quantity: Int = 2): Order {
        val order = orders.place(listOf(orders.line(1, bag, color = 0, quantity = quantity), orders.line(2, bag, color = 1)))
        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
        return order
    }

    private fun sendPhotos(order: Order) {
        orders.change(order.id) { it.attachPhotos(listOf(photo(1, 1), photo(2, 2)), operator, at(10)).getOrNull()!! }
        orders.change(order.id) { it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(20)).getOrNull()!! }
    }

    private fun variantSold(bag: Product, color: Int): Int = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.soldCount).where { ProductVariantQmTbl.variantId eq bag.variants[color].id.value }.single()[ProductVariantQmTbl.soldCount]
    }

    private fun OrderQm.kinds() = timeline.map { it.kind }

    @Test
    fun `the draft changes only the panel's columns - no timeline entry, the status and photo status unchanged`() {
        val order = paidOrder()

        orders.change(order.id) { it.attachPhotos(listOf(photo(1, 1)), operator, at(10)).getOrNull()!! }

        val qm = qmOf(order)
        assertEquals(listOf(photo(1, 1)), qm.photoDraft)
        assertEquals(emptyList(), qm.photosSent)
        assertEquals(PhotoStatus.WAITING, qm.photoStatus)
        assertEquals(OrderStatus.PROCESSING, qm.status)
        assertEquals(listOf(OrderTimelineKind.PLACED, OrderTimelineKind.PAID), qm.kinds())
    }

    @Test
    fun `sent photos - PHOTO_SENT, the draft became the batch, the deadline and one PHOTOS_SENT entry`() {
        val order = paidOrder()

        sendPhotos(order)

        val qm = qmOf(order)
        assertEquals(OrderStatus.PHOTO_SENT, qm.status)
        assertEquals(PhotoStatus.SENT, qm.photoStatus)
        assertTrue(qm.photoEverSent)
        assertEquals(listOf(photo(1, 1), photo(2, 2)), qm.photosSent)
        assertEquals(emptyList(), qm.photoDraft)
        assertEquals(1, qm.photoBatch)
        assertEquals(at(20), qm.photosSentOn)
        assertEquals(at(20).plus(Duration.ofHours(24)), qm.feedbackDeadline)
        assertEquals(OrderTimelineKind.PHOTOS_SENT, qm.timeline.last().kind)
        assertEquals(OrderStatus.PHOTO_SENT, qm.timeline.last().status)
    }

    @Test
    fun `the customer's approval keeps their answer - the system's doesn't, and says so on the timeline`() {
        val byCustomer = paidOrder()
        sendPhotos(byCustomer)
        orders.change(byCustomer.id) { it.approvePhotos(false, byCustomer.userId, at(30)).getOrNull()!! }
        val bySystem = paidOrder()
        sendPhotos(bySystem)
        orders.change(bySystem.id) { it.approvePhotos(true, UserId.SYSTEM, at(20 + 24 * 60 + 1)).getOrNull()!! }

        val customers = qmOf(byCustomer)
        assertEquals(PhotoStatus.APPROVED, customers.photoStatus)
        assertEquals(OrderQm.Feedback(FeedbackDecision.APPROVE, at(30)), customers.feedback)
        assertEquals(OrderTimelineKind.PHOTOS_APPROVED, customers.timeline.last().kind)
        val systems = qmOf(bySystem)
        assertEquals(PhotoStatus.APPROVED, systems.photoStatus)
        assertNull(systems.feedback)
        assertEquals(OrderTimelineKind.PHOTOS_AUTO_APPROVED, systems.timeline.last().kind)
        assertEquals(OrderStatus.PHOTO_SENT, systems.status)
    }

    @Test
    fun `shipped, corrected, delivered - the parcel, its correction, the delivery with its note and the time to return`() {
        val order = paidOrder()
        sendPhotos(order)
        orders.change(order.id) { it.approvePhotos(false, order.userId, at(30)).getOrNull()!! }

        orders.change(order.id) { it.ship(ShippingMethodCode.POST, tracking, at(40), true, operator, at(40)).getOrNull()!! }
        val shipped = qmOf(order)
        orders.change(order.id) { it.correctShipment(TrackingCode.constructFromPersisted("TPX0000001"), at(35), false, operator, at(50)).getOrNull()!! }
        val corrected = qmOf(order)
        orders.change(order.id) { it.markDelivered(7, StatusNote.constructFromPersisted("با پیک رسید"), operator, at(60 * 30)).getOrNull()!! }
        val delivered = qmOf(order)

        assertEquals(OrderStatus.SHIPPED, shipped.status)
        assertEquals(OrderQm.Shipment(tracking, at(40), true), shipped.shipment)
        assertEquals(OrderQm.Shipment(TrackingCode.constructFromPersisted("TPX0000001"), at(35), false), corrected.shipment)
        assertEquals(OrderStatus.SHIPPED, corrected.status)
        assertEquals(OrderStatus.DELIVERED, delivered.status)
        assertEquals(at(60 * 30), delivered.deliveredOn)
        assertEquals(at(60 * 30).plus(Duration.ofDays(7)), delivered.returnableUntil)
        assertEquals(
            listOf(OrderTimelineKind.SHIPPED, OrderTimelineKind.SHIPMENT_CORRECTED, OrderTimelineKind.DELIVERED),
            delivered.kinds().takeLast(3),
        )
        assertEquals("با پیک رسید", delivered.timeline.last().note)
    }

    @Test
    fun `a cancellation takes back exactly what the payment counted as sold, product and colors - never below where it was`() {
        val bag = orders.newBag()
        val before = listOf(orders.soldCountOf(bag), variantSold(bag, 0), variantSold(bag, 1))
        val order = paidOrder(bag, quantity = 2)
        assertEquals(listOf(before[0] + 3, before[1] + 2, before[2] + 1), listOf(orders.soldCountOf(bag), variantSold(bag, 0), variantSold(bag, 1)))

        orders.change(order.id) {
            it.cancel(CancelledBy.CUSTOMER, CancelReason.CHANGED_MIND, StatusNote.constructFromPersisted("پشیمون شدم"), order.userId, at(5)).getOrNull()!!
        }

        assertEquals(before, listOf(orders.soldCountOf(bag), variantSold(bag, 0), variantSold(bag, 1)))
        val qm = qmOf(order)
        assertEquals(OrderStatus.CANCELLED, qm.status)
        assertEquals(OrderQm.Cancellation(CancelledBy.CUSTOMER, CancelReason.CHANGED_MIND, "پشیمون شدم", at(5)), qm.cancellation)
        assertEquals(OrderTimelineKind.CANCELLED, qm.timeline.last().kind)
        assertEquals("پشیمون شدم", qm.timeline.last().note)
    }

    @Test
    fun `a completed refund adds to refunded, with its amount on the timeline - the status stays`() {
        val order = paidOrder()
        orders.change(order.id) { it.cancel(CancelledBy.STAFF, null, null, operator, at(5)).getOrNull()!! }

        orders.change(order.id) { it.recordRefund(RefundId.constructFromPersisted(31), Money.constructFromPersisted(1_000_000), operator, at(60)).getOrNull()!! }
        orders.change(order.id) { it.recordRefund(RefundId.constructFromPersisted(32), Money.constructFromPersisted(500_000), operator, at(61)).getOrNull()!! }

        val qm = qmOf(order)
        assertEquals(Money.constructFromPersisted(1_500_000), qm.refunded)
        assertEquals(OrderStatus.CANCELLED, qm.status)
        assertEquals(listOf(Money.constructFromPersisted(1_000_000), Money.constructFromPersisted(500_000)), qm.timeline.takeLast(2).map { it.amount })
        assertEquals(OrderTimelineKind.REFUNDED, qm.timeline.last().kind)
    }

    @Test
    fun `a cancelled order still counts as bought for the panel, without what went back - but no longer as a purchase that stands`() {
        val customer = UserId.constructFromPersisted(424242)
        val bag = orders.newBag()
        val kept = orders.place(listOf(orders.line(1, bag)), userId = customer)
        orders.change(kept.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
        val cancelled = orders.place(listOf(orders.line(1, bag)), userId = customer)
        orders.change(cancelled.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000B2"), it.totals.payable, false, UserId.SYSTEM, at(3)) }
        orders.change(cancelled.id) { it.cancel(CancelledBy.STAFF, null, null, operator, at(4)).getOrNull()!! }
        orders.change(cancelled.id) { it.recordRefund(RefundId.constructFromPersisted(31), it.totals.payable, operator, at(5)).getOrNull()!! }

        val purchases = transaction(db) { readModel.purchasesOf(listOf(customer)) }.getValue(customer)
        assertEquals(2, purchases.ordersCount)
        assertEquals(kept.totals.payable, purchases.totalSpent)
        assertTrue(transaction(db) { readModel.hasPurchase(customer) })

        orders.change(kept.id) { it.cancel(CancelledBy.STAFF, null, null, operator, at(6)).getOrNull()!! }
        assertEquals(false, transaction(db) { readModel.hasPurchase(customer) })
    }

    @Test
    fun `each new event changes only its own order's row`() {
        val bag = orders.newBag()
        val one = paidOrder(bag)
        val other = paidOrder(bag)
        val otherBefore = qmOf(other)

        sendPhotos(one)
        orders.change(one.id) { it.cancel(CancelledBy.STAFF, null, null, operator, at(30)).getOrNull()!! }

        assertEquals(otherBefore, qmOf(other))
    }

    @Test
    fun `a payment and a cancellation of orders of the same two colors at one moment, the lines the other way round, 20 times - no deadlock`() {
        val first = orders.newBag()
        val second = orders.newBag()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val toPay = orders.place(listOf(orders.line(1, first), orders.line(2, second)))
                val toCancel = orders.place(listOf(orders.line(1, second), orders.line(2, first)))
                orders.change(toCancel.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000B2"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
                val start = CountDownLatch(1)
                val paying = pool.submit {
                    start.await()
                    orders.change(toPay.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(3)) }
                }
                val cancelling = pool.submit {
                    start.await()
                    orders.change(toCancel.id) { it.cancel(CancelledBy.STAFF, null, null, operator, at(3)).getOrNull()!! }
                }
                start.countDown()
                paying.get(30, TimeUnit.SECONDS)
                cancelling.get(30, TimeUnit.SECONDS)

                assertEquals(OrderStatus.PROCESSING, qmOf(toPay).status)
                assertEquals(OrderStatus.CANCELLED, qmOf(toCancel).status)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ============ the database's own walls ============

    private fun insertRow(status: String, extra: String = "", extraValues: String = "") = transaction(db) {
        exec(
            """
            INSERT INTO orderqm_tbl ("orderId", code, "userId", status, "placedOn", "paidOn", "holdUntil", payable, "itemsCount",
                "shippingMethod", "messengerChannel", "reserveRequested", lines, address, messenger, totals, timeline, "updatedOn"$extra)
            VALUES (987654322, 'KV-555556', 7, '$status', now(), now(), now() + interval '15 minutes', 1450000, 1,
                'POST', 'TELEGRAM', false, '[{}]', '{}', '{}', '{}', '[{"status":"PENDING_PAYMENT","kind":"PLACED"}]', now()$extraValues)
            """.trimIndent(),
        )
    }

    private fun removeRow() = transaction(db) { exec("""DELETE FROM orderqm_tbl WHERE "orderId" = 987654322""") }

    @Test
    fun `the read model refuses a shipped order without its code or time, and a cancelled one without when, or the other way round`() {
        assertFailsWith<ExposedSQLException> { insertRow("SHIPPED") }
        assertFailsWith<ExposedSQLException> { insertRow("SHIPPED", """, "trackingCode"""", ", 'TPX4821760'") }
        assertFailsWith<ExposedSQLException> { insertRow("CANCELLED") }
        assertFailsWith<ExposedSQLException> { insertRow("PROCESSING", """, "cancelledOn"""", ", now()") }
        assertFailsWith<ExposedSQLException> { insertRow("PROCESSING", """, "trackingCode", "shippedOn"""", ", 'tpx 1', now()") }
        assertFailsWith<ExposedSQLException> { insertRow("PROCESSING", ", refunded", ", 1450001") }
        insertRow("SHIPPED", """, "trackingCode", "shippedOn"""", ", 'TPX4821760', now()")
        removeRow()
        insertRow("CANCELLED", """, "cancelledOn"""", ", now()")
        removeRow()
    }
}
