package com.kiva.infrastructure.config

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

    private val valid = mapOf(
        "kiva.datasource.url" to "jdbc:postgresql://localhost:5432/kiva",
        "kiva.datasource.username" to "kiva",
        "kiva.datasource.password" to "kiva",
        "kiva.datasource.maxPoolSize" to "10",
        "kiva.auth.otpPepper" to Base64.getEncoder().encodeToString(pepper),
        "kiva.auth.maxSmsPerHour" to "300",
        "kiva.shop.freeShippingThreshold" to "3000000",
        "kiva.shop.siteUrl" to "https://kiva.ir",
        "kiva.seed.enabled" to "false",
        "kiva.http.allowedOrigins" to "http://localhost:5173",
        "kiva.http.trustedProxyHops" to "0",
        "kiva.sms.provider" to "log",
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
    fun `the pepper never shows when the settings are printed`() {
        val printed = read().auth.toString()

        assertFalse(Base64.getEncoder().encodeToString(pepper) in printed)
        assertTrue("<hidden>" in printed)
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

        val withPepper = ConfigFactory.parseMap(mapOf("kiva.auth.otpPepper" to valid.getValue("kiva.auth.otpPepper")))
            .withFallback(file)
            .resolve(noEnvironment)
        val config = AppConfig.from(HoconApplicationConfig(withPepper))
        assertEquals(SmsProvider.LOG, config.sms.provider)
        assertEquals(300, config.auth.maxSmsPerHour)
    }
}
