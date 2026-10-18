package com.kiva.infrastructure.auth.sms

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Faraz SMS adapter against a pretend Faraz (Ktor's MockEngine), in the shapes of its web service's documentation
 * (docs.iranpayamak.com, «Send Pattern-Based SMS»): what it sends, and how it reads each kind of answer. No network.
 */
class FarazSmsSenderTest {

    /** Not a real key. */
    private val key = "TestOnlyFarazKey0123456789abcdefABCDEF"
    private val config = FarazSmsConfig("https://api.iranpayamak.com", key, "90008361", "vdXmsAfAQs")
    private val sara = IranianMobileNumber.constructFromPersisted("09121234567")
    private val code = OtpCode.of(48213)

    /** What the adapter sent: each call with its body. */
    private val sent = Collections.synchronizedList(mutableListOf<Pair<HttpRequestData, JsonObject>>())

    private fun sender(
        config: FarazSmsConfig = this.config,
        timeouts: FarazSmsSender.Timeouts = FarazSmsSender.Timeouts.DEFAULT,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { sentOk() },
    ) = FarazSmsSender(
        config,
        MockEngine { call ->
            sent += call to Json.parseToJsonElement((call.body as TextContent).text).jsonObject
            answer(call)
        },
        timeouts,
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    /** The documentation's success: 201 with the request's id. */
    private fun MockRequestHandleScope.sentOk() = json("""{"status":"success","data":3201457,"messages":"پیامک با موفقیت ارسال شد"}""", HttpStatusCode.Created)

    @Test
    fun `a login code goes to the pattern endpoint with the key in Api-Key, the pattern, the code as its variable, and the line`() {
        runBlocking { sender().sendCode(sara, code, OtpPurpose.LOGIN) }

        val (call, body) = sent.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals("https://api.iranpayamak.com/ws/v1/sms/pattern", call.url.toString())
        assertEquals(key, call.headers["Api-Key"])
        assertEquals("application/json", call.headers[HttpHeaders.Accept])
        assertEquals(
            Json.parseToJsonElement(
                """{"code":"vdXmsAfAQs","attributes":{"code":"48213"},"recipient":"09121234567","line_number":"90008361","number_format":"english"}""",
            ),
            body,
        )
    }

    @Test
    fun `a phone change's codes use the same pattern`() {
        runBlocking {
            val sms = sender()
            sms.sendCode(sara, code, OtpPurpose.PHONE_CHANGE_CURRENT)
            sms.sendCode(IranianMobileNumber.constructFromPersisted("09351239876"), OtpCode.of(70412), OtpPurpose.PHONE_CHANGE_NEW)
        }

        assertEquals(listOf("vdXmsAfAQs", "vdXmsAfAQs"), sent.map { it.second["code"].toString().trim('"') })
        assertEquals(listOf("09121234567", "09351239876"), sent.map { it.second["recipient"].toString().trim('"') })
    }

    @Test
    fun `refused - SmsNotSentException with the provider's words, but neither the whole number nor the code nor the key`() {
        val refused = assertFailsWith<SmsNotSentException> {
            runBlocking {
                sender { json("""{"status":"error","data":null,"messages":"اعتبار کافی نیست برای 09121234567 کد 48213"}""", HttpStatusCode.UnprocessableEntity) }
                    .sendCode(sara, code, OtpPurpose.LOGIN)
            }
        }

        val message = refused.message.orEmpty()
        assertTrue("0912***4567" in message, message)
        assertTrue("422" in message && "اعتبار کافی نیست" in message, message)
        assertFalse("9121234567" in message || "48213" in message || key in message, message)
    }

    @Test
    fun `an answer that isn't a success - 200 without status success, a server error, or not JSON - is not sent`() {
        listOf<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>(
            { json("""{"status":"error","data":0,"messages":"invalid pattern"}""", HttpStatusCode.OK) },
            { json("""{"status":"success"}""", HttpStatusCode.InternalServerError) },
            { respond("<html>bad gateway</html>", HttpStatusCode.BadGateway) },
            { json("""{"data":1}""", HttpStatusCode.Created) },
        ).forEach { answer ->
            assertFailsWith<SmsNotSentException> { runBlocking { sender(answer = answer).sendCode(sara, code, OtpPurpose.LOGIN) } }
        }
    }

    @Test
    fun `no answer in time or no connection - not sent, said so without the number`() {
        val slow = assertFailsWith<SmsNotSentException> {
            runBlocking {
                sender(timeouts = FarazSmsSender.Timeouts(Duration.ofMillis(100), Duration.ofMillis(100))) { delay(2_000); sentOk() }
                    .sendCode(sara, code, OtpPurpose.LOGIN)
            }
        }
        val down = assertFailsWith<SmsNotSentException> { runBlocking { sender { throw IOException("connection refused") }.sendCode(sara, code, OtpPurpose.LOGIN) } }

        listOf(slow, down).forEach { assertTrue("unreachable" in it.message.orEmpty() && "9121234567" !in it.message.orEmpty(), it.message) }
    }

    @Test
    fun `without their patterns the account-moved notice and «پرداخت موفق» are not sent, and nothing is called`() {
        runBlocking {
            val sms = sender()
            sms.sendPhoneChangedNotice(sara, "9876")
            sms.sendOrderPaid(sara, OrderCode.constructFromPersisted("KV-218340"))
            sms.sendPhotosSent(sara, OrderCode.constructFromPersisted("KV-218340"))
        }

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `with their patterns they are sent - the new number's ending, and the order's code`() {
        val withAll = FarazSmsConfig(
            "https://api.iranpayamak.com", key, "90008361", "vdXmsAfAQs", phoneChangedPattern = "pHoNe1234x", orderPaidPattern = "oRdEr5678y",
            photosSentPattern = "pH0t05ent1",
        )
        runBlocking {
            val sms = sender(withAll)
            sms.sendPhoneChangedNotice(sara, "9876")
            sms.sendOrderPaid(sara, OrderCode.constructFromPersisted("KV-218340"))
            sms.sendPhotosSent(sara, OrderCode.constructFromPersisted("KV-218340"))
        }

        assertEquals(
            listOf(
                """{"code":"pHoNe1234x","attributes":{"ending":"9876"}}""", """{"code":"oRdEr5678y","attributes":{"order":"KV-218340"}}""",
                """{"code":"pH0t05ent1","attributes":{"order":"KV-218340"}}""",
            ),
            sent.map { (_, body) -> JsonObject(body.filterKeys { it == "code" || it == "attributes" }).toString() },
        )
    }

    @Test
    fun `the key is in no log line and not in toString, and the number shows only masked`() {
        val logger = LoggerFactory.getLogger(FarazSmsSender::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val printed = try {
            val sms = sender()
            runBlocking { sms.sendCode(sara, code, OtpPurpose.LOGIN) }
            runCatching { runBlocking { sender { json("""{"status":"error","messages":"no"}""", HttpStatusCode.BadRequest) }.sendCode(sara, code, OtpPurpose.LOGIN) } }
            sms.toString() + config.toString()
        } finally {
            logger.detachAppender(appender)
        }

        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { "0912***4567" in it && "sent" in it }, lines.toString())
        assertFalse(lines.any { key in it || "9121234567" in it || "48213" in it }, lines.toString())
        assertFalse(key in printed)
        assertTrue("apiKey=<hidden>" in printed)
    }

    @Test
    fun `settings that can't work stop the boot`() {
        assertFailsWith<IllegalArgumentException> { FarazSmsConfig("https://api.iranpayamak.com/", key, "90008361", "vdXmsAfAQs") }
        assertFailsWith<IllegalArgumentException> { FarazSmsConfig("api.iranpayamak.com", key, "90008361", "vdXmsAfAQs") }
        assertFailsWith<IllegalArgumentException> { FarazSmsConfig("https://api.iranpayamak.com", " ", "90008361", "vdXmsAfAQs") }
        assertFailsWith<IllegalArgumentException> { FarazSmsConfig("https://api.iranpayamak.com", key, "line", "vdXmsAfAQs") }
        assertFailsWith<IllegalArgumentException> { FarazSmsConfig("https://api.iranpayamak.com", key, "90008361", "%code%") }
    }
}
