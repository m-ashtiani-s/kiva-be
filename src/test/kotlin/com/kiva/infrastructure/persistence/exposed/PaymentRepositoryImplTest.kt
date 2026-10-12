package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.payment.RandomPaymentIds
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payment attempts stored in kiva_test and read back, each step in its own transaction as in the app.
 *
 * Nothing is deleted afterwards: a payment is a financial record and its log is append-only, so the database refuses to
 * remove either; the test database is wiped when a test run starts. Every test uses orders of its own (fresh ids from
 * orderid_seq) and fresh authorities, so tests never meet each other's rows.
 */
class PaymentRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = PaymentRepositoryImpl()
    private val ids = RandomPaymentIds()
    private val random = SecureRandom()
    private val now = Instant.parse("2036-08-01T09:00:00Z")
    private val sara = UserId.constructFromPersisted(7)

    private fun newOrder(): OrderId = transaction(db) { OrderIdGeneratorImpl().nextOrderId() }

    private fun newAuthority() = GatewayAuthority.constructFromPersisted("A" + (1..35).map { random.nextInt(10) }.joinToString(""))

    private fun redirectOf(authority: GatewayAuthority) =
        PaymentRedirect("https://payment.zarinpal.com/pg/StartPay/${authority.value}", PaymentRedirect.Method.GET)

    private fun newPayment(order: OrderId = newOrder()) =
        Payment.start(ids.nextPaymentPublicId(), order, sara, PaymentGatewayCode.ZARINPAL, Money.constructFromPersisted(1_525_000), now)

    private fun stored(payment: Payment = newPayment()): Payment = payment.also { transaction(db) { repository.add(it) } }

    /** Locks the attempt, changes it and saves the change: one request. */
    private fun change(payment: Payment, change: (Payment) -> Unit): Payment = transaction(db) {
        val locked = repository.lock(payment.publicId)!!
        change(locked)
        repository.save(locked)
        locked
    }

    private fun Payment.state(): List<Any?> = listOf(
        publicId, orderId, userId, gateway, amount, status, authority, redirect, refId, cardMask, failureReason, createdOn, redirectedOn, verifyingSince,
        settledOn,
    )

    private fun confirmed(order: OrderId = newOrder(), refId: String = "${random.nextInt(900_000_000) + 100_000_000}"): Payment {
        val payment = stored(newPayment(order))
        val authority = newAuthority()
        change(payment) { it.redirected(authority, redirectOf(authority), now.plusSeconds(1)) }
        change(payment) { it.startVerifying(now.plusSeconds(60)) }
        return change(payment) { it.succeed(GatewayRefId.constructFromPersisted(refId), CardMask.constructFromPersisted("6037-99**-****-1234"), now.plusSeconds(61)) }
    }

    @Test
    fun `a new attempt reads back with every value`() {
        val payment = stored()

        assertEquals(payment.state(), transaction(db) { repository.find(payment.publicId) }?.state())
    }

    @Test
    fun `every step is written, and the row's version goes up with each`() {
        val payment = confirmed()

        val loaded = transaction(db) { repository.find(payment.publicId) }!!

        assertEquals(payment.state(), loaded.state())
        assertEquals(PaymentStatus.SUCCEEDED, loaded.status)
        assertEquals(3, loaded.version)
    }

    @Test
    fun `a callback finds its attempt by the gateway and the authority, and nothing by another gateway's`() {
        val payment = stored()
        val authority = newAuthority()
        change(payment) { it.redirected(authority, redirectOf(authority), now) }

        assertEquals(payment.publicId, transaction(db) { repository.findByAuthority(PaymentGatewayCode.ZARINPAL, authority) }?.publicId)
        assertNull(transaction(db) { repository.findByAuthority(PaymentGatewayCode.SAMAN, authority) })
        assertNull(transaction(db) { repository.findByAuthority(PaymentGatewayCode.ZARINPAL, newAuthority()) })
    }

    @Test
    fun `an order's attempts are locked together, oldest first, and only that order's`() {
        val order = newOrder()
        val attempts = List(3) { stored(newPayment(order)) }
        stored()

        assertEquals(attempts.map { it.publicId }, transaction(db) { repository.lockAttemptsOf(order) }.map { it.publicId })
        assertEquals(emptyList(), transaction(db) { repository.lockAttemptsOf(newOrder()) })
    }

    @Test
    fun `while one transaction holds an order's attempts, another waits for them and then sees what the first did`() {
        val order = newOrder()
        val first = stored(newPayment(order))
        stored(newPayment(order))
        val locked = CountDownLatch(1)
        val waited = AtomicLong()

        val holder = thread {
            transaction(db) {
                repository.lockAttemptsOf(order)
                locked.countDown()
                Thread.sleep(500)
                repository.lock(first.publicId)!!.also { it.expire(now.plusSeconds(900)) }.let(repository::save)
            }
        }
        locked.await()
        val seen = transaction(db) {
            val started = System.nanoTime()
            repository.lockAttemptsOf(order).also { waited.set((System.nanoTime() - started) / 1_000_000) }
        }
        holder.join()

        assertTrue(waited.get() >= 300, "waited only ${waited.get()} ms")
        assertEquals(PaymentStatus.EXPIRED, seen.first().status)
    }

    @Test
    fun `a save after someone else's is refused - the row no longer has the version that was read`() {
        val payment = stored()
        val first = transaction(db) { repository.find(payment.publicId) }!!
        val second = transaction(db) { repository.find(payment.publicId) }!!
        first.expire(now.plusSeconds(900))
        second.startFailed(PaymentFailureReason.GATEWAY_UNREACHABLE, now.plusSeconds(5))

        transaction(db) { repository.save(first) }

        assertFailsWith<ConcurrentChangeException> { transaction(db) { repository.save(second) } }
        assertEquals(PaymentStatus.EXPIRED, transaction(db) { repository.find(payment.publicId) }?.status)
    }

    // ============ the database's own walls ============

    @Test
    fun `one gateway session is one attempt - a second attempt with the same authority is refused`() {
        val authority = newAuthority()
        change(stored()) { it.redirected(authority, redirectOf(authority), now) }

        assertFailsWith<ExposedSQLException> { change(stored()) { it.redirected(authority, redirectOf(authority), now) } }
    }

    @Test
    fun `one confirmation pays one attempt - the same reference number twice is refused`() {
        confirmed(refId = "777000111")

        assertFailsWith<ExposedSQLException> { confirmed(refId = "777000111") }
    }

    @Test
    fun `an order is paid once - a second confirmed attempt of the same order is refused`() {
        val order = newOrder()
        confirmed(order)

        assertFailsWith<ExposedSQLException> { confirmed(order) }
    }

    @Test
    fun `an order is confirmed one attempt at a time - a second attempt of the same order being confirmed is refused`() {
        val order = newOrder()
        fun verifying(): Payment {
            val payment = stored(newPayment(order))
            val authority = newAuthority()
            change(payment) { it.redirected(authority, redirectOf(authority), now) }
            return change(payment) { it.startVerifying(now.plusSeconds(60)) }
        }
        val first = verifying()

        assertFailsWith<ExposedSQLException> { verifying() }
        // Once the first is settled, another may be confirmed.
        change(first) { it.fail(PaymentFailureReason.GATEWAY_DECLINED, now.plusSeconds(61)) }
        assertEquals(PaymentStatus.VERIFYING, verifying().status)
    }

    @Test
    fun `an attempt sent to the gateway without its session is refused`() {
        val payment = stored()

        assertFailsWith<ExposedSQLException> {
            transaction(db) { exec("""UPDATE payment_tbl SET status = 'REDIRECTED' WHERE "publicId" = '${payment.publicId.value}'""") }
        }
    }

    // ============ the log ============

    @Test
    fun `the log keeps every exchange, and refuses to change or lose one`() {
        val payment = stored()
        transaction(db) {
            repository.appendLog(payment.publicId, PaymentLogEntry(PaymentLogEntry.Kind.REQUEST, "OK", now, gatewayCode = 100, httpStatus = 200, duration = Duration.ofMillis(412)))
            repository.appendLog(payment.publicId, PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, "UNREACHABLE", now.plusSeconds(60)))
        }
        val paymentRow = transaction(db) { PaymentTbl.select(PaymentTbl.id).where { PaymentTbl.publicId eq payment.publicId.value }.single()[PaymentTbl.id].value }

        val rows = transaction(db) {
            PaymentLogTbl.selectAll().where { PaymentLogTbl.paymentId eq paymentRow }.orderBy(PaymentLogTbl.id)
                .map { listOf(it[PaymentLogTbl.kind], it[PaymentLogTbl.outcome], it[PaymentLogTbl.gatewayCode], it[PaymentLogTbl.httpStatus], it[PaymentLogTbl.durationMs]) }
        }
        assertEquals(
            listOf(listOf(PaymentLogEntry.Kind.REQUEST, "OK", 100, 200, 412), listOf(PaymentLogEntry.Kind.VERIFY, "UNREACHABLE", null, null, null)),
            rows,
        )

        val update = assertFailsWith<ExposedSQLException> { transaction(db) { exec("""UPDATE paymentlog_tbl SET outcome = 'OK' WHERE "paymentId" = $paymentRow""") } }
        val delete = assertFailsWith<ExposedSQLException> { transaction(db) { exec("""DELETE FROM paymentlog_tbl WHERE "paymentId" = $paymentRow""") } }
        listOf(update, delete).forEach { assertTrue("append-only" in it.message.orEmpty(), it.message) }
        assertEquals(2, transaction(db) { PaymentLogTbl.selectAll().where { PaymentLogTbl.paymentId eq paymentRow }.count() })
    }
}
