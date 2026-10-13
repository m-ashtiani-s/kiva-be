package com.kiva.infrastructure.payment

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayInquiry
import com.kiva.application.port.GatewayPaymentRequest
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakePaymentGatewayTest {

    private val gateway = FakePaymentGateway(PaymentConfig.DEFAULT)
    private val amount = Money.constructFromPersisted(1_525_000)
    private val request = GatewayPaymentRequest(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), OrderCode.constructFromPersisted("KV-218340"), amount)

    private suspend fun opened(): GatewayAuthority = gateway.requestPayment(request).getOrNull()!!.authority

    @Test
    fun `a session sends the shopper to the pretend bank's page on this server`() = runTest {
        val redirect = gateway.requestPayment(request).getOrNull()!!

        assertTrue(redirect.authority.value.startsWith("FAKE-"))
        assertEquals(PaymentRedirect("http://localhost:8080/dev/fake-gateway/${redirect.authority.value}", PaymentRedirect.Method.GET), redirect.redirect)
        assertEquals(FakePaymentGateway.State.IN_BANK, gateway.session(redirect.authority.value)?.state)
    }

    @Test
    fun `the page sends the shopper back to Zarinpal's callback, and the callback reads back as paid or not`() = runTest {
        val authority = opened()

        val ok = gateway.callbackUrl(authority.value, paid = true)

        assertEquals("http://localhost:8080/api/v1/payment-callbacks/zarinpal?Authority=${authority.value}&Status=OK", ok)
        assertEquals(true, gateway.readCallback(mapOf("Authority" to authority.value, "Status" to "OK"))?.paid)
        assertEquals(false, gateway.readCallback(mapOf("Authority" to authority.value, "Status" to "NOK"))?.paid)
        assertNull(gateway.readCallback(mapOf("Authority" to authority.value)))
        assertNull(gateway.readCallback(mapOf("Authority" to "'; DROP TABLE", "Status" to "OK")))
    }

    @Test
    fun `verify confirms only a paid session, with its own amount, and a second time says it was confirmed already`() = runTest {
        val authority = opened()

        assertEquals(GatewayFailure.Declined(-51), gateway.verify(authority, amount).failureOrNull())
        gateway.pay(authority.value)
        assertEquals(GatewayFailure.AmountMismatch, gateway.verify(authority, Money.constructFromPersisted(1_524_000)).failureOrNull())
        val first = gateway.verify(authority, amount).getOrNull()!!
        val second = gateway.verify(authority, amount).getOrNull()!!

        assertFalse(first.alreadyConfirmed)
        assertTrue(second.alreadyConfirmed)
        assertEquals(first.refId, second.refId)
        assertEquals("6037-99**-****-1234", first.cardMask?.value)
    }

    @Test
    fun `a cancelled session is never confirmed, and paying after cancelling is too late`() = runTest {
        val authority = opened()

        assertTrue(gateway.cancel(authority.value))
        assertFalse(gateway.pay(authority.value))
        assertEquals(GatewayFailure.Declined(-51), gateway.verify(authority, amount).failureOrNull())
    }

    @Test
    fun `an inquiry tells how the session stands, without confirming it`() = runTest {
        val authority = opened()
        val states = mutableListOf<GatewayInquiry?>(gateway.inquire(authority).getOrNull())
        gateway.pay(authority.value)
        states += gateway.inquire(authority).getOrNull()
        gateway.verify(authority, amount)
        states += gateway.inquire(authority).getOrNull()

        assertEquals(listOf<GatewayInquiry?>(GatewayInquiry.IN_BANK, GatewayInquiry.PAID, GatewayInquiry.VERIFIED), states.toList())
        assertEquals(GatewayFailure.Declined(-55), gateway.inquire(GatewayAuthority.constructFromPersisted("FAKE-UNKNOWN")).failureOrNull())
    }

    @Test
    fun `it can be told not to answer or to refuse, and it counts its calls`() = runTest {
        gateway.mode = FakePaymentGateway.Mode.UNREACHABLE
        assertEquals(GatewayFailure.Unreachable, gateway.requestPayment(request).failureOrNull())
        gateway.mode = FakePaymentGateway.Mode.REJECTING
        assertEquals(GatewayFailure.Rejected(-10), gateway.requestPayment(request).failureOrNull())
        gateway.mode = FakePaymentGateway.Mode.NORMAL
        gateway.verify(opened(), amount)

        assertEquals(3, gateway.callsOf(FakePaymentGateway.REQUEST))
        assertEquals(1, gateway.callsOf(FakePaymentGateway.VERIFY))
        assertEquals(0, gateway.callsOf(FakePaymentGateway.INQUIRE))
    }

    @Test
    fun `it can be slow`() = runBlocking {
        gateway.delay = Duration.ofMillis(200)
        val started = System.nanoTime()

        gateway.requestPayment(request)

        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(200))
    }

    /** The architecture test of roadmap section 10.4: a gateway call inside a transaction fails, whoever makes it. */
    @Test
    fun `a call inside a database transaction fails, so it can't be put there by mistake`() = runBlocking {
        val transactions = ExposedTransactionManager(TestDatabase.database)
        val authority = opened()

        listOf<suspend () -> Unit>(
            { gateway.requestPayment(request) },
            { gateway.verify(authority, amount) },
            { gateway.inquire(authority) },
        ).forEach { call ->
            val error = assertFailsWith<IllegalStateException> {
                transactions.inTransaction<Unit, Nothing> {
                    call()
                    Result.success(Unit)
                }
            }
            assertTrue("inside a database transaction" in error.message.orEmpty(), error.message)
        }
        assertTrue(gateway.requestPayment(request).isSuccess, "outside a transaction it is fine")
    }

    @Test
    fun `it says loudly on startup that it is the fake one`() {
        val logger = LoggerFactory.getLogger(FakePaymentGateway::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            FakePaymentGateway(PaymentConfig.DEFAULT)
        } finally {
            logger.detachAppender(appender)
        }

        val warning = appender.list.single()
        assertEquals("WARN", warning.level.toString())
        assertTrue("fake" in warning.formattedMessage)
    }
}
