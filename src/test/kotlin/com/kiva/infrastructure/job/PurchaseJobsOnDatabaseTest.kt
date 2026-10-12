package com.kiva.infrastructure.job

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.DeleteStaleGuestCartsUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.idempotency.usecase.command.DeleteOldIdempotencyKeysUc
import com.kiva.application.usecase.order.usecase.command.ExpireUnpaidOrderUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.httpserver.controller.httprest.web.PurchaseTestStack
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.IdempotencyKeyTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.seed.SeededCatalog
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The purchase's scheduled jobs over the real stack on kiva_test, one run at a time, with a clock the test moves: the
 * scheduler itself is off in tests (kiva.jobs.enabled is only read at boot), and its own test is JobSchedulerTest. Step
 * 16 of the purchase roadmap is done when these pass.
 */
class PurchaseJobsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val jobs = purchase.jobs

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }

    /** An order of customer [n] and its first attempt, sent to the gateway at the moment it was placed. */
    private class Placed(val userId: UserId, val orderCode: OrderCode, val orderId: OrderId, val paymentId: String, val authority: String?)

    private fun placed(n: Int): Placed {
        val userId = purchase.customer(phone(n))
        runBlocking { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), nilaOlive, 1)) }.getOrNull() ?: error("not added")
        val key = UUID.randomUUID().toString()
        val command = PlaceOrderUc.Cmd(
            userId = userId, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = null,
            newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null),
            newAddressAsDefault = null, saveNewAddress = false, messenger = PlaceOrderUc.MessengerFields("TELEGRAM", "09121112233", null, null, saveAsDefault = false),
            shippingMethod = "POST", reserve = null, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = null, acceptTerms = true,
        )
        val order = runBlocking { purchase.placeOrder.execute(command) }.getOrNull() ?: error("not placed")
        val paymentId = checkNotNull(order.paymentId)
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)) }
        val orderId = transaction(purchase.db) { purchase.payments.find(paymentId)!!.orderId }
        return Placed(userId, order.orderCode, orderId, paymentId.value, authorityOf(paymentId.value))
    }

    private fun authorityOf(paymentId: String): String? = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.authority]
    }

    private fun back(authority: String, status: String) = runBlocking {
        purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authority, "Status" to status)))
    }

    private fun statusOf(placed: Placed): OrderStatus? = transaction(purchase.db) { purchase.orderQm.findOfCustomer(placed.userId, placed.orderCode)?.status }

    private fun paymentOf(paymentId: String) = transaction(purchase.db) { purchase.payments.find(PaymentPublicId.constructFromPersisted(paymentId))!! }

    private fun eventsOf(aggregate: String, id: String, type: String): Long = transaction(purchase.db) {
        EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateName eq aggregate) and (EventStoreTbl.aggregateId eq id) and (EventStoreTbl.eventType eq type) }.count()
    }

    /** The stock's ReservationReleased events that name [placed]'s order. */
    private fun releasesOf(placed: Placed): Int = transaction(purchase.db) {
        EventStoreTbl.select(EventStoreTbl.payload)
            .where { (EventStoreTbl.aggregateName eq "INVENTORY") and (EventStoreTbl.eventType eq "ReservationReleased") }
            .count { placed.orderCode.value in it[EventStoreTbl.payload] }
    }

    /**
     * The exchanges of one attempt with the gateway, oldest first. The jobs' runs are global and kiva_test keeps other
     * tests' payments (a payment is never deleted), so a test looks at its own attempts' log, not at the gateway's counts.
     */
    private fun logOf(paymentId: String): List<String> = transaction(purchase.db) {
        val row = PaymentTbl.select(PaymentTbl.id).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.id].value
        PaymentLogTbl.select(PaymentLogTbl.kind, PaymentLogTbl.outcome).where { PaymentLogTbl.paymentId eq row }.orderBy(PaymentLogTbl.id)
            .map { "${it[PaymentLogTbl.kind]} ${it[PaymentLogTbl.outcome]}" }
    }

    private fun expireRun() = runBlocking { jobs.expireUnpaidOrders() }

    private fun reconcileRun() = runBlocking { jobs.reconcilePayments() }

    /** Runs [work] on [n] threads released together, so the database really sees the calls at the same moment. */
    private fun <T> atOnce(n: Int, work: suspend (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        return try {
            val calls = List(n) { index -> pool.submit<T> { start.await(); runBlocking { work(index) } } }
            start.countDown()
            calls.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `the purchase's jobs and when they run`() {
        assertEquals(
            listOf(
                "expire-unpaid-orders" to Schedule.Every(Duration.ofMinutes(1)),
                "reconcile-payments" to Schedule.Every(Duration.ofMinutes(2)),
                "delete-stale-guest-carts" to Schedule.DailyAt(LocalTime.of(4, 0), ShopCalendar.ZONE),
                "delete-old-idempotency-keys" to Schedule.Every(Duration.ofHours(1)),
            ),
            jobs.all().map { it.name to it.schedule },
        )
    }

    // ============ expiring unpaid orders ============

    @Test
    fun `15 minutes and a second later, an order with no payment going on expires - its stock comes back and its attempts expire`() {
        val stock = purchase.availableOf(nilaOlive)
        val cancelled = placed(1201)
        back(cancelled.authority!!, "NOK")
        purchase.gateway.mode = FakePaymentGateway.Mode.UNREACHABLE
        val neverSent = placed(1202)
        purchase.gateway.mode = FakePaymentGateway.Mode.NORMAL
        assertEquals(stock - 2, purchase.availableOf(nilaOlive))

        clock.advance(Duration.ofMinutes(15).minusSeconds(1))
        expireRun()
        assertEquals(listOf(OrderStatus.PAYMENT_FAILED, OrderStatus.PENDING_PAYMENT), listOf(statusOf(cancelled), statusOf(neverSent)), "not before its time")

        clock.advance(Duration.ofSeconds(2))
        expireRun()

        assertEquals(listOf(OrderStatus.EXPIRED, OrderStatus.EXPIRED), listOf(statusOf(cancelled), statusOf(neverSent)))
        assertEquals(stock, purchase.availableOf(nilaOlive))
        assertEquals(PaymentStatus.CANCELLED, paymentOf(cancelled.paymentId).status, "an attempt that ended stays as it ended")
        assertEquals(PaymentStatus.START_FAILED, paymentOf(neverSent.paymentId).status)
    }

    @Test
    fun `an attempt on the bank's page less than 20 minutes, or one being confirmed, keeps the order - until it ends`() {
        val stock = purchase.availableOf(nilaOlive)
        val onTheBankPage = placed(1203)
        val beingConfirmed = placed(1204)
        purchase.gateway.pay(beingConfirmed.authority!!)
        purchase.gateway.mode = FakePaymentGateway.Mode.UNREACHABLE
        back(beingConfirmed.authority, "OK")
        purchase.gateway.mode = FakePaymentGateway.Mode.NORMAL
        assertEquals(PaymentStatus.VERIFYING, paymentOf(beingConfirmed.paymentId).status)

        clock.advance(Duration.ofMinutes(19))
        expireRun()

        assertEquals(listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_PAYMENT), listOf(statusOf(onTheBankPage), statusOf(beingConfirmed)))
        assertEquals(stock - 2, purchase.availableOf(nilaOlive))

        // The shopper on the bank's page took too long; the one being confirmed is still being confirmed.
        clock.advance(Duration.ofMinutes(1))
        expireRun()
        assertEquals(OrderStatus.EXPIRED, statusOf(onTheBankPage))
        assertEquals(PaymentStatus.EXPIRED to PaymentFailureReason.EXPIRED, paymentOf(onTheBankPage.paymentId).let { it.status to it.failureReason })
        assertEquals(OrderStatus.PENDING_PAYMENT, statusOf(beingConfirmed))

        // Its confirmation ends: paid, after its time to pay, and its bag was kept for it.
        reconcileRun()
        assertEquals(OrderStatus.PROCESSING, statusOf(beingConfirmed))
        assertEquals(stock - 1, purchase.availableOf(nilaOlive))
        expireRun()
        assertEquals(OrderStatus.PROCESSING, statusOf(beingConfirmed))
    }

    @Test
    fun `run again, or twice at once, the expiry changes nothing twice - one OrderExpired, the stock given back once`() {
        val stock = purchase.availableOf(nilaOlive)
        val placed = placed(1205)
        back(placed.authority!!, "NOK")
        clock.advance(Duration.ofMinutes(16))

        atOnce(4) { purchase.expireUnpaidOrder.execute(ExpireUnpaidOrderUc.Cmd(placed.orderId)) }
        atOnce(2) { jobs.expireUnpaidOrders() }
        expireRun()

        assertEquals(OrderStatus.EXPIRED, statusOf(placed))
        assertEquals(1, eventsOf("ORDER", placed.orderId.value.toString(), "OrderExpired"))
        assertEquals(1, releasesOf(placed))
        assertEquals(stock, purchase.availableOf(nilaOlive))
    }

    // ============ reconciling payments ============

    @Test
    fun `a confirmation the gateway didn't answer is asked again - SUCCEEDED, the order paid and «پرداخت موفق» sent`() {
        val placed = placed(1206)
        purchase.gateway.pay(placed.authority!!)
        purchase.gateway.mode = FakePaymentGateway.Mode.UNREACHABLE
        back(placed.authority, "OK")
        purchase.gateway.mode = FakePaymentGateway.Mode.NORMAL

        clock.advance(Duration.ofMinutes(1))
        reconcileRun()
        assertEquals(PaymentStatus.VERIFYING, paymentOf(placed.paymentId).status, "not before 2 minutes: the callback had its chance")

        clock.advance(Duration.ofMinutes(1))
        reconcileRun()

        assertEquals(PaymentStatus.SUCCEEDED, paymentOf(placed.paymentId).status)
        assertEquals(OrderStatus.PROCESSING, statusOf(placed))
        assertEquals(listOf(placed.orderCode.value), purchase.auth.sms.paid.map { it.second.value })
    }

    @Test
    fun `a shopper who paid and closed the tab - the gateway says PAID, the payment is confirmed and the order paid`() {
        val placed = placed(1207)
        purchase.gateway.pay(placed.authority!!)

        clock.advance(Duration.ofMinutes(3))
        reconcileRun()

        assertEquals(PaymentStatus.SUCCEEDED, paymentOf(placed.paymentId).status)
        assertEquals(OrderStatus.PROCESSING, statusOf(placed))
        assertEquals(listOf("REQUEST OK", "INQUIRY PAID", "VERIFY OK"), logOf(placed.paymentId))
    }

    @Test
    fun `cancelled at the bank and never back - the attempt fails and the order's try with it, and the stock stays held`() {
        val stock = purchase.availableOf(nilaOlive)
        val placed = placed(1208)
        purchase.gateway.cancel(placed.authority!!)

        clock.advance(Duration.ofMinutes(3))
        reconcileRun()

        assertEquals(PaymentStatus.FAILED to PaymentFailureReason.GATEWAY_DECLINED, paymentOf(placed.paymentId).let { it.status to it.failureReason })
        assertEquals(OrderStatus.PAYMENT_FAILED, statusOf(placed))
        assertEquals(stock - 1, purchase.availableOf(nilaOlive))
    }

    @Test
    fun `still at the bank - left alone, and once abandoned it is closed and never asked about again`() {
        val atTheBank = placed(1209)

        clock.advance(Duration.ofMinutes(10))
        reconcileRun()
        assertEquals(PaymentStatus.REDIRECTED, paymentOf(atTheBank.paymentId).status)

        clock.advance(Duration.ofMinutes(10))
        reconcileRun()
        assertEquals(PaymentStatus.EXPIRED, paymentOf(atTheBank.paymentId).status)

        clock.advance(Duration.ofMinutes(2))
        reconcileRun()
        assertEquals(listOf("REQUEST OK", "INQUIRY IN_BANK", "INQUIRY IN_BANK"), logOf(atTheBank.paymentId), "never asked about again")
    }

    @Test
    fun `run twice at once, the reconciliation confirms once and pays once`() {
        val placed = placed(1211)
        purchase.gateway.pay(placed.authority!!)
        clock.advance(Duration.ofMinutes(3))

        atOnce(3) { jobs.reconcilePayments() }

        assertEquals(PaymentStatus.SUCCEEDED, paymentOf(placed.paymentId).status)
        assertEquals(1, eventsOf("ORDER", placed.orderId.value.toString(), "OrderPaid"))
        assertEquals(1, purchase.auth.sms.paid.size)
    }

    // ============ the exception: paid for an order that couldn't take it (section 10.5) ============

    /** An attempt being confirmed whose order expired under it: what the rules never let happen, made by hand. */
    private fun paidForAnOrderGone(n: Int): Placed {
        val placed = placed(n)
        purchase.gateway.pay(placed.authority!!)
        purchase.gateway.unreachable += FakePaymentGateway.VERIFY
        back(placed.authority, "OK")
        purchase.gateway.unreachable.clear()
        clock.advance(Duration.ofMinutes(16))
        transaction(purchase.db) {
            val order = purchase.orders.get(placed.orderId)!!
            order.expire(UserId.SYSTEM, clock.now).getOrNull()!!
            purchase.orders.save(order)
        }
        return placed
    }

    @Test
    fun `confirmed for an order that couldn't take it - the money is given back at once, and the attempt is REFUNDED`() {
        val placed = paidForAnOrderGone(1214)

        reconcileRun()

        assertEquals(PaymentStatus.REFUNDED, paymentOf(placed.paymentId).status)
        assertEquals(listOf("REQUEST OK", "CALLBACK OK", "VERIFY UNREACHABLE", "VERIFY OK", "REVERSE OK"), logOf(placed.paymentId))
        assertEquals(FakePaymentGateway.State.REVERSED, purchase.gateway.session(placed.authority!!)?.state)
        assertEquals(OrderStatus.EXPIRED, statusOf(placed))
        assertEquals(emptyList(), purchase.auth.sms.paid, "nothing was bought")
    }

    @Test
    fun `when the gateway won't give it back, the attempt stays SUCCEEDED for support to give back by hand`() {
        val placed = paidForAnOrderGone(1215)
        purchase.gateway.unreachable += FakePaymentGateway.REVERSE

        reconcileRun()

        assertEquals(PaymentStatus.SUCCEEDED, paymentOf(placed.paymentId).status)
        assertEquals(listOf("REQUEST OK", "CALLBACK OK", "VERIFY UNREACHABLE", "VERIFY OK", "REVERSE UNREACHABLE"), logOf(placed.paymentId))
    }

    // ============ the clean-ups ============

    @Test
    fun `guests' carts - an empty one untouched a day goes, one with bags waits a month, a customer's never goes`() {
        fun guestCart(withBag: Boolean): String = runBlocking {
            val opened = purchase.openCart.execute(OpenCartUc.Cmd(CartCaller.Guest(null))).getOrNull()!!
            if (withBag) purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Guest(opened.newToken!!.reveal()), nilaOlive, 1)).getOrNull()!!
            opened.cartId.value
        }
        fun exists(publicId: String) = transaction(purchase.db) { CartTbl.selectAll().where { CartTbl.publicId eq publicId }.count() == 1L }

        val empty = guestCart(withBag = false)
        val withBag = guestCart(withBag = true)
        val customer = purchase.customer(phone(1212))
        val customersCart = runBlocking { purchase.openCart.execute(OpenCartUc.Cmd(CartCaller.Customer(customer))) }.getOrNull()!!.cartId.value

        clock.advance(Duration.ofHours(23))
        runBlocking { purchase.deleteStaleGuestCarts.execute(DeleteStaleGuestCartsUc.Cmd) }
        assertTrue(exists(empty), "not a day yet")

        clock.advance(Duration.ofHours(2))
        runBlocking { purchase.deleteStaleGuestCarts.execute(DeleteStaleGuestCartsUc.Cmd) }
        assertEquals(listOf(false, true, true), listOf(exists(empty), exists(withBag), exists(customersCart)))

        clock.advance(Duration.ofDays(30))
        runBlocking { purchase.deleteStaleGuestCarts.execute(DeleteStaleGuestCartsUc.Cmd) }
        assertEquals(listOf(false, true), listOf(exists(withBag), exists(customersCart)))
    }

    @Test
    fun `an Idempotency-Key is kept 24 hours, then forgotten`() {
        val placed = placed(1213)
        fun keys() = transaction(purchase.db) { IdempotencyKeyTbl.selectAll().where { IdempotencyKeyTbl.userId eq placed.userId.value }.count() }
        assertEquals(1, keys())

        clock.advance(Duration.ofHours(23))
        runBlocking { purchase.deleteOldIdempotencyKeys.execute(DeleteOldIdempotencyKeysUc.Cmd) }
        assertEquals(1, keys())

        clock.advance(Duration.ofHours(2))
        runBlocking { purchase.deleteOldIdempotencyKeys.execute(DeleteOldIdempotencyKeysUc.Cmd) }
        assertEquals(0, keys())
    }
}
