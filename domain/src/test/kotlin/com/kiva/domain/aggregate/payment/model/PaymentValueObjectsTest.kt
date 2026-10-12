package com.kiva.domain.aggregate.payment.model

import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PaymentValueObjectsTest {

    @Test
    fun `a callback's authority is taken only when it can be one, so nothing is looked up for anything else`() {
        assertEquals("A00000000000000000000000000217885159", GatewayAuthority.of(" A00000000000000000000000000217885159 ")?.value)
        assertEquals("FAKE-0A1B", GatewayAuthority.of("FAKE-0A1B")?.value)
        listOf(null, "", "A0' OR 1=1", "x".repeat(101), "<script>").forEach { assertNull(GatewayAuthority.of(it), "$it") }
    }

    @Test
    fun `a public payment id is pay_ and 12 capital letters and digits`() {
        assertEquals("pay_01J9X2K7AB3C", PaymentPublicId.constructFromPersisted("pay_01J9X2K7AB3C").value)
        listOf("pay_01j9x2k7ab3c", "pay_123", "cart_01J9X2K7AB").forEach { assertFailsWith<IllegalArgumentException>(it) { PaymentPublicId.constructFromPersisted(it) } }
    }

    @Test
    fun `a card is kept masked only - never a whole number`() {
        assertEquals("603799******1234", CardMask.constructFromPersisted("603799******1234").value)
        assertFailsWith<IllegalArgumentException> { CardMask.constructFromPersisted("6037991234561234") }
        assertFailsWith<IllegalArgumentException> { CardMask.constructFromPersisted("6037-99**-**12-1234") }
    }

    @Test
    fun `a reference number is the bank's letters and digits`() {
        assertEquals("201843917", GatewayRefId.constructFromPersisted("201843917").value)
        assertFailsWith<IllegalArgumentException> { GatewayRefId.constructFromPersisted("") }
    }

    @Test
    fun `a GET redirect is only an address, a POST one may carry its form`() {
        PaymentRedirect("https://sep.shaparak.ir/OnlinePG/OnlinePG", PaymentRedirect.Method.POST, mapOf("Token" to "abc"))

        assertFailsWith<IllegalArgumentException> { PaymentRedirect("https://pay.example", PaymentRedirect.Method.GET, mapOf("Token" to "abc")) }
        assertFailsWith<IllegalArgumentException> { PaymentRedirect("javascript:alert(1)", PaymentRedirect.Method.GET) }
    }

    @Test
    fun `a log entry has a short code of how it went, and nothing negative`() {
        val at = Instant.parse("2026-10-12T08:00:00Z")
        PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, "OK", at, gatewayCode = 100, httpStatus = 200, duration = Duration.ofMillis(840))

        assertFailsWith<IllegalArgumentException> { PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, "ok", at) }
        assertFailsWith<IllegalArgumentException> { PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, "OK", at, httpStatus = 99) }
        assertFailsWith<IllegalArgumentException> { PaymentLogEntry(PaymentLogEntry.Kind.VERIFY, "OK", at, duration = Duration.ofMillis(-1)) }
    }
}
