package com.kiva.infrastructure.config

import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.infrastructure.payment.PaymentProvider
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading the settings at boot: a missing or weak secret, or a value that can't be right, stops the app with a clear message. */
class AppConfigTest {

    private val pepper = ByteArray(32) { it.toByte() }
    private val jwtSecret = ByteArray(32) { (it + 100).toByte() }

    private val valid = mapOf(
        "kiva.datasource.url" to "jdbc:postgresql://localhost:5432/kiva",
        "kiva.datasource.username" to "kiva",
        "kiva.datasource.password" to "kiva",
        "kiva.datasource.maxPoolSize" to "10",
        "kiva.auth.otpPepper" to Base64.getEncoder().encodeToString(pepper),
        "kiva.auth.jwtSecret" to Base64.getEncoder().encodeToString(jwtSecret),
        "kiva.auth.maxSmsPerHour" to "300",
        "kiva.shop.siteUrl" to "https://kiva.ir",
        "kiva.seed.enabled" to "false",
        "kiva.http.allowedOrigins" to "http://localhost:5173",
        "kiva.http.trustedProxyHops" to "0",
        "kiva.sms.provider" to "log",
        "kiva.payment.provider" to "fake",
        "kiva.payment.callbackBaseUrl" to "http://localhost:8080",
        "kiva.payment.resultUrl" to "http://localhost:3100/checkout/result",
        "kiva.payment.zarinpal.sandbox" to "true",
    )

    private fun read(vararg changes: Pair<String, String?>): AppConfig {
        val settings = valid.toMutableMap()
        changes.forEach { (key, value) -> if (value == null) settings.remove(key) else settings[key] = value }
        return AppConfig.from(MapApplicationConfig(*settings.toList().toTypedArray()))
    }

    private fun refusal(vararg changes: Pair<String, String?>): String =
        assertFailsWith<IllegalArgumentException> { read(*changes) }.message.orEmpty()

    @Test
    fun `valid settings are read`() {
        val config = read()

        assertContentEquals(pepper, config.auth.otpPepper)
        assertContentEquals(jwtSecret, config.auth.jwtSecret)
        assertEquals(300, config.auth.maxSmsPerHour)
        assertEquals(SmsProvider.LOG, config.sms.provider)
        assertEquals(0, config.http.trustedProxyHops)
    }

    @Test
    fun `without a pepper the app doesn't start, and says how to make one`() {
        val message = refusal("kiva.auth.otpPepper" to null)

        assertTrue("KIVA_OTP_PEPPER" in message)
        assertTrue("openssl rand -base64 32" in message)
    }

    @Test
    fun `without a JWT secret the app doesn't start either`() {
        assertTrue("KIVA_JWT_SECRET" in refusal("kiva.auth.jwtSecret" to null))
        assertTrue("at least 32 bytes" in refusal("kiva.auth.jwtSecret" to Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test
    fun `the JWT secret can't be the pepper, so one leak doesn't give away both`() {
        assertTrue("must be different" in refusal("kiva.auth.jwtSecret" to Base64.getEncoder().encodeToString(pepper)))
    }

    @Test
    fun `a pepper under 32 bytes or not in base64 is refused`() {
        assertTrue("at least 32 bytes" in refusal("kiva.auth.otpPepper" to Base64.getEncoder().encodeToString(ByteArray(31))))
        assertTrue("not base64" in refusal("kiva.auth.otpPepper" to "not base64 at all!"))
    }

    @Test
    fun `trusted proxy hops are 0 or 1`() {
        assertEquals(1, read("kiva.http.trustedProxyHops" to "1").http.trustedProxyHops)
        assertTrue("trustedProxyHops" in refusal("kiva.http.trustedProxyHops" to "2"))
    }

    @Test
    fun `an unknown SMS provider stops the app instead of quietly sending nothing`() {
        assertTrue("kavenegar" in refusal("kiva.sms.provider" to "kavenegar"))
    }

    @Test
    fun `the secrets never show when the settings are printed`() {
        val printed = read().auth.toString()

        assertFalse(Base64.getEncoder().encodeToString(pepper) in printed)
        assertFalse(Base64.getEncoder().encodeToString(jwtSecret) in printed)
        assertEquals("AuthConfig(otpPepper=<hidden>, jwtSecret=<hidden>, maxSmsPerHour=300, bootstrapAdminPhone=null)", printed)
    }

    @Test
    fun `the first admin's phone is optional, read in any form, and never printed whole`() {
        assertEquals(null, read().auth.bootstrapAdminPhone)
        assertEquals(null, read("kiva.auth.bootstrapAdminPhone" to " ").auth.bootstrapAdminPhone)

        val auth = read("kiva.auth.bootstrapAdminPhone" to "+98 912 123 4567").auth
        assertEquals("09121234567", auth.bootstrapAdminPhone?.value)
        assertTrue("0912***4567" in auth.toString())
        assertFalse("09121234567" in auth.toString())
    }

    @Test
    fun `a first admin's phone that isn't a mobile number stops the boot`() {
        assertTrue("KIVA_BOOTSTRAP_ADMIN_PHONE" in refusal("kiva.auth.bootstrapAdminPhone" to "12345"))
    }

    /**
     * The real application.conf: every key is where AppConfig looks, and the pepper has no default in it. Resolved
     * without the environment, so a KIVA_OTP_PEPPER set on this machine can't hide a default.
     */
    @Test
    fun `application conf has every setting, and no pepper unless the environment gives one`() {
        val file = ConfigFactory.parseResources("application.conf")
        val noEnvironment = ConfigResolveOptions.noSystem()

        val withoutPepper = HoconApplicationConfig(file.resolve(noEnvironment))
        assertTrue("KIVA_OTP_PEPPER" in assertFailsWith<IllegalArgumentException> { AppConfig.from(withoutPepper) }.message.orEmpty())

        val secrets = mapOf(
            "kiva.auth.otpPepper" to valid.getValue("kiva.auth.otpPepper"),
            "kiva.auth.jwtSecret" to valid.getValue("kiva.auth.jwtSecret"),
        )
        val withPepper = ConfigFactory.parseMap(secrets)
            .withFallback(file)
            .resolve(noEnvironment)
        val config = AppConfig.from(HoconApplicationConfig(withPepper))
        assertEquals(SmsProvider.LOG, config.sms.provider)
        assertEquals(300, config.auth.maxSmsPerHour)
        assertEquals(PaymentProvider.FAKE, config.payment.provider)
        assertEquals("http://localhost:8080", config.payment.callbackBaseUrl)
        assertTrue(config.payment.zarinpal.sandbox)
    }

    // ============ payments ============

    private val merchantId = "1344b5d4-0048-11e8-94db-005056a205be"

    @Test
    fun `payments are taken by the fake gateway in development, and come back to this API's callback`() {
        val payment = read().payment

        assertEquals(PaymentProvider.FAKE, payment.provider)
        assertEquals("http://localhost:8080/api/v1/payment-callbacks/zarinpal", payment.callbackUrlOf(PaymentGatewayCode.ZARINPAL))
        assertEquals("http://localhost:3100/checkout/result", payment.resultUrl)
        assertEquals(null, payment.zarinpal.merchantId)
    }

    @Test
    fun `zarinpal without its merchant id, or with one that isn't a UUID, stops the boot`() {
        assertTrue("KIVA_ZARINPAL_MERCHANT_ID" in refusal("kiva.payment.provider" to "zarinpal"))
        assertTrue("KIVA_ZARINPAL_MERCHANT_ID" in refusal("kiva.payment.provider" to "zarinpal", "kiva.payment.zarinpal.merchantId" to "12345"))

        val zarinpal = read("kiva.payment.provider" to "zarinpal", "kiva.payment.zarinpal.merchantId" to merchantId).payment
        assertEquals(merchantId, zarinpal.zarinpal.merchantId)
    }

    @Test
    fun `an unknown payment provider, an address with a path or a slash at the end, or a sandbox typo stops the boot`() {
        assertTrue("paypal" in refusal("kiva.payment.provider" to "paypal"))
        assertTrue("KIVA_API_PUBLIC_URL" in refusal("kiva.payment.callbackBaseUrl" to "https://api.kiva.ir/"))
        assertTrue("KIVA_API_PUBLIC_URL" in refusal("kiva.payment.callbackBaseUrl" to "https://api.kiva.ir/api"))
        assertTrue("KIVA_PAYMENT_RESULT_URL" in refusal("kiva.payment.resultUrl" to "https://kiva.ir/checkout/result/"))
        assertTrue("KIVA_PAYMENT_RESULT_URL" in refusal("kiva.payment.resultUrl" to "kiva.ir/checkout/result"))
        assertFailsWith<IllegalArgumentException> { read("kiva.payment.zarinpal.sandbox" to "yes") }
    }

    @Test
    fun `the merchant id never shows when the settings are printed`() {
        val printed = read("kiva.payment.provider" to "zarinpal", "kiva.payment.zarinpal.merchantId" to merchantId).payment.toString()

        assertFalse(merchantId in printed)
        assertTrue("merchantId=<hidden>" in printed)
    }
}
