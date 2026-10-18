package com.kiva.infrastructure.auth.sms

import com.kiva.application.port.SmsSender
import com.kiva.common.logging.getLogger
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Faraz SMS (`kiva.sms.provider = faraz`), through its web service (docs.iranpayamak.com): every SMS is a **pattern**
 * approved in the panel in advance, sent with `POST {baseUrl}/ws/v1/sms/pattern`, the key in the `Api-Key` header, and
 * the pattern's variables in `attributes`. Success is HTTP 201 with `{"status": "success", "data": …}`.
 *
 * | message | pattern | its variables |
 * |---|---|---|
 * | a login or phone-change code | [FarazSmsConfig.codePattern] («کد ورود: %code%») | `code` |
 * | «your account moved» to the old phone | [FarazSmsConfig.phoneChangedPattern], optional | `ending` (the new number's last 4 digits) |
 * | «پرداخت موفق» | [FarazSmsConfig.orderPaidPattern], optional | `order` (the order's code, `KV-218340`) |
 * | «عکس کیفت فرستاده شد» | [FarazSmsConfig.photosSentPattern], optional | `order` |
 * | «پولت برگشت» | [FarazSmsConfig.refundCompletedPattern], optional | `order` |
 * | «لغو شد» | [FarazSmsConfig.orderCancelledPattern], optional | `order` |
 *
 * A message without its pattern is not sent, and a WARN says so: the panel approves each pattern before it can be used,
 * and the code that sends the message goes on all the same (the phone still changes, the order is still paid).
 *
 * Failing to send throws [SmsNotSentException], as the port says. Its message, like every log line here, has neither
 * the key, the code, nor a whole number: the phone is masked, and the provider's own message has every run of digits
 * long enough to be a number or a code cut out. Called after the commit, never inside a transaction.
 */
class FarazSmsSender(private val config: FarazSmsConfig, engine: HttpClientEngine, timeouts: Timeouts = Timeouts.DEFAULT) : SmsSender {

    private val logger = getLogger()

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connect.toMillis()
            requestTimeoutMillis = timeouts.request.toMillis()
            socketTimeoutMillis = timeouts.request.toMillis()
        }
    }

    init {
        logger.info("SMS provider is Faraz SMS ($this)")
        if (config.phoneChangedPattern == null) logger.warn("Faraz SMS: no pattern for the «your account moved» notice (KIVA_FARAZ_PHONE_CHANGED_PATTERN); it won't be sent")
        if (config.orderPaidPattern == null) logger.warn("Faraz SMS: no pattern for «پرداخت موفق» (KIVA_FARAZ_ORDER_PAID_PATTERN); it won't be sent")
        if (config.photosSentPattern == null) logger.warn("Faraz SMS: no pattern for «عکس کیفت فرستاده شد» (KIVA_FARAZ_PHOTOS_SENT_PATTERN); it won't be sent")
        if (config.refundCompletedPattern == null) logger.warn("Faraz SMS: no pattern for «پولت برگشت» (KIVA_FARAZ_REFUND_COMPLETED_PATTERN); it won't be sent")
        if (config.orderCancelledPattern == null) logger.warn("Faraz SMS: no pattern for «لغو شد» (KIVA_FARAZ_ORDER_CANCELLED_PATTERN); it won't be sent")
    }

    /** How long to wait for the provider: to connect, and for the whole call. */
    data class Timeouts(val connect: Duration, val request: Duration) {
        companion object {
            val DEFAULT = Timeouts(connect = Duration.ofSeconds(5), request = Duration.ofSeconds(10))
        }
    }

    override suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose) {
        send("a $purpose code", config.codePattern, to, mapOf("code" to code.value))
    }

    override suspend fun sendPhoneChangedNotice(oldPhone: IranianMobileNumber, newPhoneEnding: String) {
        val pattern = config.phoneChangedPattern ?: return notSent("the «your account moved» notice", oldPhone)
        send("the «your account moved» notice", pattern, oldPhone, mapOf("ending" to newPhoneEnding))
    }

    override suspend fun sendOrderPaid(to: IranianMobileNumber, orderCode: OrderCode) {
        val pattern = config.orderPaidPattern ?: return notSent("«پرداخت موفق» of order ${orderCode.value}", to)
        send("«پرداخت موفق» of order ${orderCode.value}", pattern, to, mapOf("order" to orderCode.value))
    }

    override suspend fun sendPhotosSent(to: IranianMobileNumber, orderCode: OrderCode) {
        val pattern = config.photosSentPattern ?: return notSent("«عکس کیفت فرستاده شد» of order ${orderCode.value}", to)
        send("«عکس کیفت فرستاده شد» of order ${orderCode.value}", pattern, to, mapOf("order" to orderCode.value))
    }

    override suspend fun sendRefundCompleted(to: IranianMobileNumber, orderCode: OrderCode) {
        val pattern = config.refundCompletedPattern ?: return notSent("«پولت برگشت» of order ${orderCode.value}", to)
        send("«پولت برگشت» of order ${orderCode.value}", pattern, to, mapOf("order" to orderCode.value))
    }

    override suspend fun sendOrderCancelled(to: IranianMobileNumber, orderCode: OrderCode) {
        val pattern = config.orderCancelledPattern ?: return notSent("«لغو شد» of order ${orderCode.value}", to)
        send("«لغو شد» of order ${orderCode.value}", pattern, to, mapOf("order" to orderCode.value))
    }

    override fun toString(): String =
        "FarazSmsSender(baseUrl=${config.baseUrl}, line=${config.lineNumber}, codePattern=${config.codePattern}, " +
            "phoneChangedPattern=${config.phoneChangedPattern}, orderPaidPattern=${config.orderPaidPattern}, photosSentPattern=${config.photosSentPattern}, refundCompletedPattern=${config.refundCompletedPattern}, " +
            "orderCancelledPattern=${config.orderCancelledPattern})"

    // ============ inside ============

    private suspend fun send(what: String, pattern: String, to: IranianMobileNumber, attributes: Map<String, String>) {
        val body = buildJsonObject {
            put("code", pattern)
            putJsonObject("attributes") { attributes.forEach { (name, value) -> put(name, value) } }
            put("recipient", to.value)
            put("line_number", config.lineNumber)
            put("number_format", "english")
        }
        val (status, text) = try {
            val response = client.post("${config.baseUrl}/ws/v1/sms/pattern") {
                header(API_KEY_HEADER, config.apiKey)
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(body.toString())
            }
            response.status to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SmsNotSentException("Faraz SMS: $what to ${to.masked()} not sent, the provider is unreachable (${e.javaClass.simpleName})")
        }

        val answer = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val outcome = answer?.text("status")
        if (status.isSuccess() && outcome == SUCCESS) {
            logger.info("Faraz SMS: $what to ${to.masked()} sent (request ${answer.text("data") ?: "-"})")
            return
        }
        val said = answer?.let { it.text("messages") ?: it.text("message") }
        throw SmsNotSentException("Faraz SMS: $what to ${to.masked()} refused, HTTP ${status.value} ${outcome ?: "(no status)"}: ${loggable(said)}")
    }

    private fun notSent(what: String, to: IranianMobileNumber) {
        logger.warn("Faraz SMS: $what to ${to.masked()} not sent, it has no pattern configured")
    }

    /** The provider's own words, short, without any run of digits long enough to be a phone number or a code. */
    private fun loggable(message: String?): String =
        message?.replace(LONG_DIGITS, "…")?.take(MAX_MESSAGE)?.ifBlank { null } ?: "(no message)"

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val API_KEY_HEADER = "Api-Key"
        const val SUCCESS = "success"
        const val MAX_MESSAGE = 200
        val LONG_DIGITS = Regex("[0-9۰-۹٠-٩]{4,}")
    }
}

/**
 * Faraz SMS's settings (`kiva.sms.faraz`). [apiKey] is a secret: from the environment only (`KIVA_FARAZ_API_KEY`),
 * never in git, never in a log, not in [toString]. The pattern codes are not secrets.
 *
 * @property baseUrl the web service's address, without a `/` at the end.
 * @property lineNumber the line patterns are sent from.
 * @property codePattern the approved pattern of login and phone-change codes; its variable is `code`.
 * @property phoneChangedPattern optional; its variable is `ending`.
 * @property orderPaidPattern optional; its variable is `order`.
 * @property photosSentPattern optional; its variable is `order`.
 * @property refundCompletedPattern optional; its variable is `order`.
 * @property orderCancelledPattern optional; its variable is `order`.
 */
class FarazSmsConfig(
    val baseUrl: String,
    val apiKey: String,
    val lineNumber: String,
    val codePattern: String,
    val phoneChangedPattern: String? = null,
    val orderPaidPattern: String? = null,
    val photosSentPattern: String? = null,
    val refundCompletedPattern: String? = null,
    val orderCancelledPattern: String? = null,
) {
    init {
        require(baseUrl.matches(Regex("https?://[^/\\s]+(/[^\\s]*)?")) && !baseUrl.endsWith("/")) {
            "kiva.sms.faraz.baseUrl must be an http(s) address without a / at the end, was '$baseUrl'"
        }
        require(apiKey.isNotBlank() && apiKey.none(Char::isWhitespace)) { "KIVA_FARAZ_API_KEY is empty or has spaces in it" }
        require(lineNumber.matches(Regex("\\+?[0-9]{4,20}"))) { "kiva.sms.faraz.lineNumber must be a line number, was '$lineNumber'" }
        listOfNotNull(codePattern, phoneChangedPattern, orderPaidPattern, photosSentPattern, refundCompletedPattern, orderCancelledPattern).forEach { pattern ->
            require(pattern.matches(Regex("[A-Za-z0-9_-]{2,64}"))) { "A Faraz SMS pattern code must be letters and digits, was '$pattern'" }
        }
    }

    override fun toString(): String =
        "FarazSmsConfig(baseUrl=$baseUrl, apiKey=<hidden>, lineNumber=$lineNumber, codePattern=$codePattern, " +
            "phoneChangedPattern=$phoneChangedPattern, orderPaidPattern=$orderPaidPattern, photosSentPattern=$photosSentPattern, refundCompletedPattern=$refundCompletedPattern, orderCancelledPattern=$orderCancelledPattern)"
}

/** The provider didn't take the SMS. Its message has no key, code or whole number in it. */
class SmsNotSentException(message: String) : RuntimeException(message)
