package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.GatewayPaymentRequest
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.payment.PaymentConfig
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pretend bank's page of the fake gateway: what it shows, and where each button sends the shopper. */
class DevFakeGatewayTest {

    private val gateway = FakePaymentGateway(PaymentConfig.DEFAULT)
    private val authority = runBlocking {
        val request = GatewayPaymentRequest(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), OrderCode.constructFromPersisted("KV-218340"), Money.constructFromPersisted(1_525_000))
        gateway.requestPayment(request).getOrNull()!!.authority.value
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing { devFakeGateway(gateway) }
        }
        block()
    }

    private fun ApplicationTestBuilder.browser() = createClient { followRedirects = false }

    @Test
    fun `the page shows the order, the amount and the two buttons`() = app {
        val page = browser().get("/dev/fake-gateway/$authority")

        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        listOf("KV-218340", "۱٬۵۲۵٬۰۰۰ تومان", "پرداخت موفق", "انصراف", "/dev/fake-gateway/$authority/pay", "/dev/fake-gateway/$authority/cancel")
            .forEach { assertTrue(it in html, it) }
    }

    @Test
    fun `«پرداخت موفق» records the payment and goes back to Zarinpal's callback with OK`() = app {
        val back = browser().post("/dev/fake-gateway/$authority/pay")

        assertEquals(HttpStatusCode.Found, back.status)
        assertEquals("http://localhost:8080/api/v1/payment-callbacks/zarinpal?Authority=$authority&Status=OK", back.headers[HttpHeaders.Location])
        assertEquals(FakePaymentGateway.State.PAID, gateway.session(authority)?.state)
    }

    @Test
    fun `«انصراف» goes back with NOK, and paying after it is too late`() = app {
        val back = browser().post("/dev/fake-gateway/$authority/cancel")
        val late = browser().post("/dev/fake-gateway/$authority/pay")

        assertEquals("http://localhost:8080/api/v1/payment-callbacks/zarinpal?Authority=$authority&Status=NOK", back.headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Conflict, late.status)
        assertEquals(FakePaymentGateway.State.CANCELLED, gateway.session(authority)?.state)
    }

    @Test
    fun `a session the pretend bank never opened is not found`() = app {
        assertEquals(HttpStatusCode.NotFound, browser().get("/dev/fake-gateway/FAKE-UNKNOWN").status)
        assertEquals(HttpStatusCode.NotFound, browser().post("/dev/fake-gateway/FAKE-UNKNOWN/pay").status)
    }
}
