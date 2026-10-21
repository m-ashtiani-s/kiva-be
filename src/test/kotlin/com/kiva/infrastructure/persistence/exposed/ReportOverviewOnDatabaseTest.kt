package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.dto.ReportOverviewDto
import com.kiva.application.usecase.report.usecase.query.GetReportOverviewUc
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeTarget
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * The panel's report (roadmap section 9.3) on kiva_test, over orders made by the real domain and projector: every field
 * against a number worked out by hand from twelve orders (paid, cancelled, refunded, reserved, with photos and change
 * requests, on different days, some just outside the range). The orders live in 2031, where no other test writes.
 */
class ReportOverviewOnDatabaseTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val report = GetReportOverviewUc(ExposedTransactionManager(db), ReportQmRepositoryImpl())
    private val operator = UserId.constructFromPersisted(900)

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun customer(n: Int) = UserId.constructFromPersisted(880_000L + n)
    private fun utc(text: String): Instant = Instant.parse(text)
    private fun toman(amount: Long) = Money.constructFromPersisted(amount)
    private fun note(text: String) = StatusNote.constructFromPersisted(text)

    private fun overview(from: String, to: String): ReportOverviewDto =
        runBlocking { report.execute(GetReportOverviewUc.Qry(from, to)) }.getOrNull() ?: error("refused")

    /** [quantity] of [bag]'s [color]-th color at [price] each, not on sale. */
    private fun line(lineId: Int, bag: Product, price: Long, quantity: Int = 1, color: Int = 0): OrderLine =
        orders.line(lineId, bag, color = color, quantity = quantity, price = price, compareAt = null)

    /** Totals of [lines] with a flat 100,000 for shipping and [code] off by PAEEZ15: payable = subtotal − code + 100,000. */
    private fun totalsOf(lines: List<OrderLine>, code: Long): OrderTotals {
        val subtotal = lines.map { it.totals.total }.sum()
        return OrderTotals.constructFromPersisted(
            itemsCount = lines.sumOf { it.quantity.value }, linesCount = lines.size, itemsCompareAtTotal = subtotal, productDiscount = Money.ZERO,
            subtotal = subtotal, codeDiscount = toman(code), shippingCost = SHIPPING, shippingIsFree = false, shippingFreeReason = null,
            payable = subtotal - toman(code) + SHIPPING, discountCode = if (code > 0) DiscountCodeText.constructFromPersisted("PAEEZ15") else null,
        )
    }

    private var payments = 0
    private fun nextPayment() = PaymentPublicId.constructFromPersisted("pay_" + (++payments).toString().padStart(12, '0'))

    /** Placed ten minutes before [paidAt] and paid then; held in reservation group [group] when given. */
    private fun paid(
        customer: UserId,
        lines: List<OrderLine>,
        paidAt: Instant,
        code: Long = 0,
        shipping: ShippingMethodCode = ShippingMethodCode.POST,
        channel: MessengerChannel = MessengerChannel.TELEGRAM,
        group: Long? = null,
    ): Order {
        val order = orders.place(lines, userId = customer, at = paidAt.minusSeconds(600), totals = totalsOf(lines, code), shippingMethod = shipping, channel = channel)
        return orders.change(order.id) {
            it.markPaid(nextPayment(), it.totals.payable, group != null, UserId.SYSTEM, paidAt, group?.let(ReservationGroupId::constructFromPersisted)).getOrNull()!!
        }
    }

    /** A photo of each line attached and sent at [at]. */
    private fun photosSent(order: Order, at: Instant) {
        orders.change(order.id) {
            val photos = it.lines.map { line -> OrderPhoto(MediaId.constructFromPersisted("med_" + line.lineId.toString().padStart(22, '0')), line.lineId, MediaView.FRONT, line.lineId) }
            it.attachPhotos(photos, operator, at).getOrNull()!!
        }
        orders.change(order.id) { it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at).getOrNull()!! }
    }

    /** «یه چیز دیگه» asked at [at] and refused ten minutes later. */
    private fun otherChangeRejected(order: Order, at: Instant) {
        orders.change(order.id) { it.requestChange(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر")), null, order.userId, at).getOrNull()!! }
        orders.change(order.id) { it.rejectChange(note("نداریم"), operator, at.plusSeconds(600)).getOrNull()!! }
    }

    private var refunds = 0L
    private fun refunded(order: Order, amount: Long, at: Instant) =
        orders.change(order.id) { it.recordRefund(RefundId.constructFromPersisted(++refunds + 880_000), toman(amount), operator, at).getOrNull()!! }

    /**
     * The twelve orders, Tehran days 2031-03-01 to 2031-03-03 being the range (Tehran is UTC+03:30, so the range is
     * 2031-02-28T20:30Z to 2031-03-03T20:30Z). Bags: A, B, C (ids ascending). Shipping is 100,000 on every order.
     */
    private fun twelveOrders(): Triple<Product, Product, Product> {
        val a = orders.newBag()
        val b = orders.newBag()
        val c = orders.newBag()

        // 1. customer 1: A at 1,000,000 → payable 1,100,000. Photos in the range, approved by silence.
        val o1 = paid(customer(1), listOf(line(1, a, 1_000_000)), utc("2031-03-01T06:00:00Z"))
        photosSent(o1, utc("2031-03-01T08:00:00Z"))
        // 2. customer 2: 2 × A and B, PAEEZ15 150,000 off, Tipax, Bale, held for a reservation → 4,000,000 − 150,000 + 100,000 = 3,950,000.
        paid(
            customer(2), listOf(line(1, a, 1_000_000, quantity = 2), line(2, b, 2_000_000)), utc("2031-03-01T10:00:00Z"), code = 150_000,
            shipping = ShippingMethodCode.TIPAX, channel = MessengerChannel.BALE, group = 880_001,
        )
        // 3. customer 3: B, Rubika, paid 21:00Z on 03-02 — already 00:30 of 03-03 in Tehran; 300,003 back → kept 1,799,997.
        val o3 = paid(customer(3), listOf(line(1, b, 2_000_000)), utc("2031-03-02T21:00:00Z"), channel = MessengerChannel.RUBIKA)
        refunded(o3, 300_003, utc("2031-03-03T10:00:00Z"))
        // 4. customer 4: C at 500,000, paid 21:00Z on 02-28 — 00:30 of 03-01 in Tehran, so in the range → 600,000.
        paid(customer(4), listOf(line(1, c, 500_000)), utc("2031-02-28T21:00:00Z"))
        // 5. customer 5: A, paid 20:00Z on 02-28 — 23:30 of 02-28 in Tehran: outside. First photos outside too; the second batch,
        //    inside the range, isn't a first one.
        val o5 = paid(customer(5), listOf(line(1, a, 1_000_000)), utc("2031-02-28T20:00:00Z"))
        photosSent(o5, utc("2031-02-28T20:10:00Z"))
        otherChangeRejected(o5, utc("2031-02-28T20:20:00Z"))
        photosSent(o5, utc("2031-03-01T09:00:00Z"))
        // 6. customer 5 again: 3 × C, Bale → 1,600,000. Photos in the range, a change asked and refused.
        val o6 = paid(customer(5), listOf(line(1, c, 500_000, quantity = 3)), utc("2031-03-03T08:00:00Z"), channel = MessengerChannel.BALE)
        photosSent(o6, utc("2031-03-03T10:00:00Z"))
        otherChangeRejected(o6, utc("2031-03-03T10:30:00Z"))
        // 7. customer 6: B, Tipax, Rubika, photos in the range, then cancelled: out of every sum, still one of the photos sent.
        val o7 = paid(customer(6), listOf(line(1, b, 2_000_000)), utc("2031-03-01T12:00:00Z"), shipping = ShippingMethodCode.TIPAX, channel = MessengerChannel.RUBIKA)
        photosSent(o7, utc("2031-03-01T13:00:00Z"))
        orders.change(o7.id) { it.cancel(CancelledBy.STAFF, null, note("مشتری پشیمون شد"), operator, utc("2031-03-01T14:00:00Z")).getOrNull()!! }
        // 8. customer 1 again: C → 600,000. Not a new customer twice.
        paid(customer(1), listOf(line(1, c, 500_000)), utc("2031-03-03T09:00:00Z"))
        // 9. customer 7: placed in the range, never paid.
        orders.place(listOf(line(1, a, 1_000_000)), userId = customer(7), at = utc("2031-03-01T10:00:00Z"), totals = totalsOf(listOf(line(1, a, 1_000_000)), 0))
        // 10. customer 8: A, paid 20:30Z on 03-03 — exactly 00:00 of 03-04 in Tehran: outside.
        paid(customer(8), listOf(line(1, a, 1_000_000)), utc("2031-03-03T20:30:00Z"))
        // 11. customer 7: B → 2,100,000; swapped for B's black at 2,300,000, the 300,000 owed paid → kept 2,400,000; its line now black.
        val o11 = paid(customer(7), listOf(line(1, b, 2_000_000)), utc("2031-03-03T05:00:00Z"))
        photosSent(o11, utc("2031-03-03T06:00:00Z"))
        val black = b.variants[1].id
        orders.change(o11.id) {
            it.requestChange(ChangeRequest(ChangeType.COLOR, 1, black, note("مشکی")), ChangeTarget(black, b.id), o11.userId, utc("2031-03-03T06:30:00Z")).getOrNull()!!
        }
        orders.change(o11.id) {
            val old = it.lines.single()
            val swapped = old.replacedBy(b.id, black, old.item, PriceTag.constructFromPersisted(toman(2_300_000), null), reservable = true, returnable = true)
            it.replaceLine(swapped, MaxPerOrder.DEFAULT, operator, utc("2031-03-03T07:00:00Z")).getOrNull()!!
            it.applyChange(note("مشکیش رو گذاشتیم"), operator, utc("2031-03-03T07:00:00Z")).getOrNull()!!
        }
        orders.change(o11.id) { it.payBalance(nextPayment(), toman(300_000), UserId.SYSTEM, utc("2031-03-03T07:30:00Z")).getOrNull()!! }
        // 12. customer 2 again: A and C with PAEEZ15 150,000 off, Bale → 1,450,000. C taken out: its share of the code is
        //     150,000 × 500,000 ÷ 1,500,000 = 50,000, so 450,000 goes back → kept 1,000,000, code shown 100,000.
        val o12 = paid(
            customer(2), listOf(line(1, a, 1_000_000), line(2, c, 500_000)), utc("2031-03-03T06:00:00Z"), code = 150_000, channel = MessengerChannel.BALE,
        )
        photosSent(o12, utc("2031-03-03T07:00:00Z"))
        orders.change(o12.id) { it.requestChange(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null), null, o12.userId, utc("2031-03-03T07:30:00Z")).getOrNull()!! }
        orders.change(o12.id) {
            it.removeLine(operator, utc("2031-03-03T08:00:00Z")).getOrNull()!!
            val settlement = it.applyChange(null, operator, utc("2031-03-03T08:00:00Z")).getOrNull()!!
            assertEquals(toman(450_000), settlement.refund, "the hand count of order 12")
        }
        refunded(o12, 450_000, utc("2031-03-03T08:30:00Z"))
        return Triple(a, b, c)
    }

    @Test
    fun `twelve orders - every field is the number worked out by hand`() {
        val (a, b, c) = twelveOrders()

        val report = overview("2031-03-01", "2031-03-03")

        // The period's orders: 1, 2, 3, 4, 6, 8, 11 and 12.
        assertEquals(8, report.ordersCount)
        // 1,100,000 + 3,950,000 + 1,799,997 + 600,000 + 1,600,000 + 600,000 + 2,400,000 + 1,000,000
        assertEquals(toman(13_049_997), report.revenue)
        // 13,049,997 ÷ 8 = 1,631,249.625 → 1,631,250
        assertEquals(toman(1_631_250), report.averageOrderValue)
        // Customers 1, 2, 3, 4 and 7; not 5 (first bought on 02-28), 6 (cancelled only) or 8 (03-04 in Tehran).
        assertEquals(5, report.newCustomers)
        // Order 2 of 8: 12.5%.
        assertEquals(125, report.reservationSharePerMille)
        // First photos in the range: orders 1, 6, 7 (cancelled), 11 and 12; a change asked of 6, 11 and 12.
        assertEquals(400, report.mediaApprovalRatePerMille)
        assertEquals(600, report.changeRequestRatePerMille)
        assertEquals(0, report.returnRatePerMille)
        // 150,000 (order 2) + 100,000 (order 12 after C was taken out)
        assertEquals(toman(250_000), report.discountTotal)
        assertEquals(mapOf(ShippingMethodCode.TIPAX to 1, ShippingMethodCode.POST to 7, ShippingMethodCode.COURIER to 0), report.shippingMix)
        assertEquals(mapOf(MessengerChannel.RUBIKA to 1, MessengerChannel.TELEGRAM to 4, MessengerChannel.BALE to 3), report.messengerMix)
        // C: 1 + 3 + 1; A: 1 + 2 + 1 (order 12's C gone); B: 1 + 1 + order 11's black at 2,300,000.
        assertEquals(
            listOf("${c.id.value} 5 2500000", "${a.id.value} 4 4000000", "${b.id.value} 3 6300000"),
            report.topProducts.map { "${it.id} ${it.unitsSold} ${it.revenue.toman}" },
        )
        assertEquals(listOf(c.slug.value, c.sku.value), report.topProducts.first().let { listOf(it.slug, it.sku) })
        // 03-01: orders 1, 2 and 4; 03-02: none (order 3 is already 03-03 in Tehran); 03-03: orders 3, 6, 8, 11 and 12.
        assertEquals(
            listOf("2031-03-01 5650000 3", "2031-03-02 0 0", "2031-03-03 7399997 5"),
            report.dailySeries.map { "${it.date} ${it.revenue.toman} ${it.orders}" },
        )
        assertEquals(LocalDate.parse("2031-03-01") to LocalDate.parse("2031-03-03"), report.from to report.to)
    }

    @Test
    fun `a day further - order 10, paid at 00 00 of 03-04 in Tehran, is in it`() {
        twelveOrders()

        val report = overview("2031-03-04", "2031-03-04")

        assertEquals(listOf("2031-03-04 1100000 1"), report.dailySeries.map { "${it.date} ${it.revenue.toman} ${it.orders}" })
        // Customer 8's first purchase.
        assertEquals(1, report.newCustomers)
    }

    @Test
    fun `best sellers - at most five, a tie to the smaller id`() {
        val bags = List(6) { orders.newBag() }
        paid(customer(20), bags.mapIndexed { i, bag -> line(i + 1, bag, 100_000) }, utc("2031-05-01T08:00:00Z"))

        val top = overview("2031-05-01", "2031-05-01").topProducts

        assertEquals(bags.map { it.id.value }.sorted().take(5), top.map { it.id })
    }

    @Test
    fun `nothing in the range - zeros everywhere, every carrier, messenger and day`() {
        val report = overview("2031-07-01", "2031-07-02")

        assertEquals(listOf(0, 0, 0), listOf(report.ordersCount, report.newCustomers, report.mediaApprovalRatePerMille))
        assertEquals(listOf(0L, 0L, 0L), listOf(report.revenue.toman, report.averageOrderValue.toman, report.discountTotal.toman))
        assertEquals(setOf(0), report.shippingMix.values.toSet() + report.messengerMix.values.toSet())
        assertEquals(listOf(3, 3), listOf(report.shippingMix.size, report.messengerMix.size))
        assertEquals(listOf("2031-07-01 0 0", "2031-07-02 0 0"), report.dailySeries.map { "${it.date} ${it.revenue.toman} ${it.orders}" })
        assertEquals(emptyList(), report.topProducts)
    }

    private companion object {
        val SHIPPING: Money = Money.constructFromPersisted(100_000)
    }
}
