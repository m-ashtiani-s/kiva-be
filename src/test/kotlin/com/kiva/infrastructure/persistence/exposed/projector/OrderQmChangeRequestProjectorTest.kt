package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.repository.OrderTimelineKind
import com.kiva.application.repository.ResolutionDecision
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeTarget
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredOrders
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * orderqm_tbl after a change request (phase 3 step 3.1), kept in step with the orders' events on kiva_test, each change in
 * its own transaction as in the app. Two real bags, so a swapped line moves what each color counts as sold.
 */
class OrderQmChangeRequestProjectorTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val readModel = OrderQmRepositoryImpl()
    private val now = orders.now
    private val operator = UserId.constructFromPersisted(900)

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))
    private fun toman(amount: Long) = Money.constructFromPersisted(amount)
    private fun note(text: String) = StatusNote.constructFromPersisted(text)

    private fun qmOf(order: Order): OrderQm = transaction(db) { readModel.findByCode(order.code) }!!

    private fun OrderQm.kinds() = timeline.map { it.kind }

    private fun variantSold(bag: Product, color: Int): Int = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.soldCount).where { ProductVariantQmTbl.variantId eq bag.variants[color].id.value }.single()[ProductVariantQmTbl.soldCount]
    }

    /** One of [first] (its caramel) and one of [second] (its caramel), paid, with photos of both sent. */
    private fun photoSent(first: Product, second: Product, totals: OrderTotals? = null): Order {
        val order = orders.place(listOf(orders.line(1, first), orders.line(2, second)), totals = totals)
        orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, false, UserId.SYSTEM, at(2)) }
        orders.change(order.id) {
            it.attachPhotos(listOf(1, 2).map { line -> OrderPhoto(MediaId.constructFromPersisted("med_" + line.toString().padStart(22, '0')), line, MediaView.FRONT, line) }, operator, at(10))
                .getOrNull()!!
        }
        orders.change(order.id) { it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(20)).getOrNull()!! }
        return order
    }

    /** Line 1 asked as [first]'s black and carried out at [price]. */
    private fun swapToBlack(order: Order, first: Product, price: Long) {
        val black = first.variants[1].id
        orders.change(order.id) { it.requestChange(ChangeRequest(ChangeType.COLOR, 1, black, note("مشکی")), ChangeTarget(black, first.id), order.userId, at(30)).getOrNull()!! }
        orders.change(order.id) {
            val line = it.lines.first { line -> line.lineId == 1 }
            val newLine = line.replacedBy(first.id, black, line.item, PriceTag.constructFromPersisted(toman(price), null), reservable = true, returnable = true)
            it.replaceLine(newLine, MaxPerOrder.DEFAULT, operator, at(40)).getOrNull()!!
            it.applyChange(note("مشکیش رو گذاشتیم"), operator, at(40)).getOrNull()!!
        }
    }

    @Test
    fun `a change request - CHANGE_REQUESTED, its photos too, the customer's request kept as asked, one CHANGE_REQUESTED entry`() {
        val first = orders.newBag()
        val order = photoSent(first, orders.newBag())
        val black = first.variants[1].id

        orders.change(order.id) { it.requestChange(ChangeRequest(ChangeType.COLOR, 1, black, note("مشکی")), ChangeTarget(black, first.id), order.userId, at(30)).getOrNull()!! }

        val qm = qmOf(order)
        assertEquals(OrderStatus.CHANGE_REQUESTED, qm.status)
        assertEquals(PhotoStatus.CHANGE_REQUESTED, qm.photoStatus)
        assertEquals(OrderQm.Feedback(FeedbackDecision.REQUEST_CHANGE, at(30), ChangeType.COLOR, 1, black.value, "مشکی", resolution = null), qm.feedback)
        assertEquals(OrderTimelineKind.CHANGE_REQUESTED, qm.kinds().last())
    }

    @Test
    fun `rejected - PROCESSING, photos WAITING, the last batch still there, the request with its REJECT and the reason on the timeline`() {
        val order = photoSent(orders.newBag(), orders.newBag())
        orders.change(order.id) { it.requestChange(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر")), null, order.userId, at(30)).getOrNull()!! }

        orders.change(order.id) { it.rejectChange(note("بند بلندتر نداریم"), operator, at(40)).getOrNull()!! }

        val qm = qmOf(order)
        assertEquals(OrderStatus.PROCESSING, qm.status)
        assertEquals(PhotoStatus.WAITING, qm.photoStatus)
        assertEquals(2, qm.photosSent.size)
        assertEquals(OrderQm.Resolution(ResolutionDecision.REJECT, null, "بند بلندتر نداریم", at(40)), qm.feedback?.resolution)
        assertEquals("بند بلندتر", qm.feedback?.note)
        assertEquals(OrderTimelineKind.CHANGE_REJECTED, qm.timeline.last().kind)
        assertEquals("بند بلندتر نداریم", qm.timeline.last().note)
        assertEquals(OrderStatus.PROCESSING, qm.timeline.last().status)
    }

    @Test
    fun `a color swapped - the line in its place, what is sold moved from the old color to the new one, 200,000 owed, one COLOR_CHANGED entry`() {
        val first = orders.newBag()
        val order = photoSent(first, orders.newBag())
        val before = listOf(orders.soldCountOf(first), variantSold(first, 0), variantSold(first, 1))

        swapToBlack(order, first, price = 1_650_000)

        val qm = qmOf(order)
        assertEquals(listOf(first.variants[1].id, qm.lines[1].variantId), qm.lines.map { it.variantId })
        assertEquals(listOf(1, 2), qm.lines.map { it.lineId })
        assertEquals(listOf(before[0], before[1] - 1, before[2] + 1), listOf(orders.soldCountOf(first), variantSold(first, 0), variantSold(first, 1)))
        assertEquals(toman(200_000), qm.balanceDue)
        assertEquals(OrderStatus.PROCESSING, qm.status)
        assertEquals(PhotoStatus.WAITING, qm.photoStatus)
        assertEquals(OrderQm.Resolution(ResolutionDecision.APPLY, first.variants[1].id.value, "مشکیش رو گذاشتیم", at(40)), qm.feedback?.resolution)
        assertEquals(listOf(OrderTimelineKind.CHANGE_REQUESTED, OrderTimelineKind.COLOR_CHANGED), qm.kinds().takeLast(2))
        assertEquals(toman(3_100_000), qm.totalsView.subtotal)
        assertEquals(toman(200_000), qm.totalsView.balanceDue)
    }

    @Test
    fun `a line taken out - gone with its items, what it sold given back, the code's share leaving with it, one ITEM_REMOVED entry`() {
        // Two lines of 1,450,000 (crossed out 1,750,000) with a code of 290,000: 2,610,000 paid.
        val withCode = OrderTotals.constructFromPersisted(
            2, 2, toman(3_500_000), toman(600_000), toman(2_900_000), toman(290_000), Money.ZERO, true, ShippingFreeReason.THRESHOLD, toman(2_610_000),
            DiscountCodeText.constructFromPersisted("KIVA10"),
        )
        val second = orders.newBag()
        val order = photoSent(orders.newBag(), second, withCode)
        val before = listOf(orders.soldCountOf(second), variantSold(second, 0))

        orders.change(order.id) { it.requestChange(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null), null, order.userId, at(30)).getOrNull()!! }
        orders.change(order.id) {
            it.removeLine(operator, at(40)).getOrNull()!!
            it.applyChange(null, operator, at(40)).getOrNull()!!
        }

        val qm = qmOf(order)
        assertEquals(listOf(1), qm.lines.map { it.lineId })
        assertEquals(1, transaction(db) { OrderQmTbl.select(OrderQmTbl.itemsCount).where { OrderQmTbl.orderId eq order.id.value }.single()[OrderQmTbl.itemsCount] })
        assertEquals(listOf(before[0] - 1, before[1] - 1), listOf(orders.soldCountOf(second), variantSold(second, 0)))
        assertEquals(toman(145_000), qm.codeDiscountRemoved)
        assertEquals(OrderTimelineKind.ITEM_REMOVED, qm.kinds().last())
        val view = qm.totalsView
        assertEquals(listOf(toman(1_450_000), toman(145_000), toman(2_610_000), Money.ZERO), listOf(view.subtotal, view.codeDiscount, view.payable, view.balanceDue))
        assertEquals(withCode, qm.totals, "the placed totals never change")
    }

    @Test
    fun `the balance paid - nothing owed, added to what was paid, BALANCE_PAID with the amount, and the customer's spending counts it`() {
        val first = orders.newBag()
        val order = photoSent(first, orders.newBag())
        swapToBlack(order, first, price = 1_650_000)
        val spentBefore = transaction(db) { readModel.purchasesOf(listOf(order.userId)) }.getValue(order.userId).totalSpent

        orders.change(order.id) { it.payBalance(PaymentPublicId.constructFromPersisted("pay_0000000000B2"), toman(200_000), order.userId, at(50)).getOrNull()!! }

        val qm = qmOf(order)
        assertEquals(listOf(Money.ZERO, toman(200_000)), listOf(qm.balanceDue, qm.balancePaid))
        assertEquals(OrderTimelineKind.BALANCE_PAID, qm.timeline.last().kind)
        assertEquals(toman(200_000), qm.timeline.last().amount)
        assertEquals(OrderStatus.PROCESSING, qm.status)
        assertEquals(qm.totals.payable + toman(200_000), qm.totalsView.payable)
        val spentAfter: OrderQmRepository.Purchases = transaction(db) { readModel.purchasesOf(listOf(order.userId)) }.getValue(order.userId)
        assertEquals(spentBefore + toman(200_000), spentAfter.totalSpent)
    }

    @Test
    fun `cancelled while something is owed - nothing owed any more, and the swapped color's count given back, not the old one's`() {
        val first = orders.newBag()
        val order = photoSent(first, orders.newBag())
        val before = listOf(variantSold(first, 0), variantSold(first, 1))
        swapToBlack(order, first, price = 1_650_000)

        orders.change(order.id) { it.cancel(CancelledBy.STAFF, null, null, operator, at(60)).getOrNull()!! }

        val qm = qmOf(order)
        assertEquals(Money.ZERO, qm.balanceDue)
        assertEquals(listOf(before[0] - 1, before[1]), listOf(variantSold(first, 0), variantSold(first, 1)), "the payment counted caramel, the swap moved it to black, the cancellation took black back")
    }

    @Test
    fun `an answer stored before change requests (no resolution, no change) reads back with nothing of a change`() {
        val order = photoSent(orders.newBag(), orders.newBag())
        orders.change(order.id) { it.approvePhotos(false, order.userId, at(30)).getOrNull()!! }
        transaction(db) {
            exec("""UPDATE orderqm_tbl SET feedback = '{"decision":"APPROVE","changeType":null,"orderItemId":null,"desiredVariantId":null,"note":null,"at":"${at(30)}","resolution":null}' WHERE "orderId" = ${order.id.value}""")
        }

        val feedback = qmOf(order).feedback

        assertEquals(OrderQm.Feedback(FeedbackDecision.APPROVE, at(30)), feedback)
        assertNull(feedback?.resolution)
    }
}
