package com.kiva.infrastructure.config

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.infrastructure.payment.PaymentConfig
import com.kiva.infrastructure.payment.PaymentProvider
import com.kiva.infrastructure.payment.ZarinpalConfig
import io.ktor.server.config.ApplicationConfig
import java.util.Base64

/** @property auth no default: the app must not start without its secrets. */
data class AppConfig(
    val datasource: DatasourceConfig,
    val auth: AuthConfig,
    val shop: ShopConfig = ShopConfig.DEFAULT,
    val seed: SeedConfig = SeedConfig(enabled = false),
    val http: HttpConfig = HttpConfig(allowedOrigins = emptyList()),
    val sms: SmsConfig = SmsConfig(SmsProvider.LOG),
    val payment: PaymentConfig = PaymentConfig.DEFAULT,
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val ds = config.config("kiva.datasource")
            return AppConfig(
                datasource = DatasourceConfig(
                    url = ds.property("url").getString(),
                    username = ds.property("username").getString(),
                    password = ds.property("password").getString(),
                    maxPoolSize = ds.property("maxPoolSize").getString().toInt(),
                ),
                auth = AuthConfig.of(
                    otpPepperBase64 = config.propertyOrNull("kiva.auth.otpPepper")?.getString(),
                    jwtSecretBase64 = config.propertyOrNull("kiva.auth.jwtSecret")?.getString(),
                    maxSmsPerHour = config.property("kiva.auth.maxSmsPerHour").getString().toInt(),
                    bootstrapAdminPhoneRaw = config.propertyOrNull("kiva.auth.bootstrapAdminPhone")?.getString(),
                ),
                shop = ShopConfig(siteUrl = config.property("kiva.shop.siteUrl").getString()),
                // Strict: a typo such as "yes" stops the boot instead of quietly meaning false.
                seed = SeedConfig(enabled = config.property("kiva.seed.enabled").getString().toBooleanStrict()),
                http = HttpConfig.of(
                    config.property("kiva.http.allowedOrigins").getString(),
                    trustedProxyHops = config.property("kiva.http.trustedProxyHops").getString().toInt(),
                ),
                sms = SmsConfig(SmsProvider.of(config.property("kiva.sms.provider").getString())),
                payment = PaymentConfig(
                    provider = PaymentProvider.of(config.property("kiva.payment.provider").getString()),
                    callbackBaseUrl = config.property("kiva.payment.callbackBaseUrl").getString(),
                    resultUrl = config.property("kiva.payment.resultUrl").getString(),
                    zarinpal = ZarinpalConfig(
                        merchantId = config.propertyOrNull("kiva.payment.zarinpal.merchantId")?.getString()?.trim()?.takeUnless { it.isEmpty() },
                        // Strict, like the seed switch: a typo stops the boot instead of quietly meaning the real host.
                        sandbox = config.property("kiva.payment.zarinpal.sandbox").getString().toBooleanStrict(),
                    ),
                ),
            )
        }
    }
}

/**
 * Secrets and limits of login. Both secrets are required, at least 32 bytes, and different from each other: one leaked
 * secret must not give away the other.
 *
 * @property otpPepper the key of the codes' HMAC (`KIVA_OTP_PEPPER`). Kept outside the database, so a copy of the
 *   database can't be used to work the codes out.
 * @property jwtSecret the key access tokens are signed with (`KIVA_JWT_SECRET`).
 * @property maxSmsPerHour the shop's cap on SMS in an hour, all phones together.
 * @property bootstrapAdminPhone becomes ADMIN on startup while no ADMIN exists at all (`KIVA_BOOTSTRAP_ADMIN_PHONE`);
 *   for the very first start only, then removed.
 */
class AuthConfig(
    otpPepper: ByteArray,
    jwtSecret: ByteArray,
    val maxSmsPerHour: Int,
    val bootstrapAdminPhone: IranianMobileNumber? = null,
) {

    private val pepper = otpPepper.copyOf()
    private val signingKey = jwtSecret.copyOf()

    val otpPepper: ByteArray get() = pepper.copyOf()
    val jwtSecret: ByteArray get() = signingKey.copyOf()

    init {
        require(pepper.size >= MIN_SECRET_BYTES) {
            "kiva.auth.otpPepper (KIVA_OTP_PEPPER) must be at least $MIN_SECRET_BYTES bytes, was ${pepper.size}. $HOW_TO_MAKE_ONE"
        }
        require(signingKey.size >= MIN_SECRET_BYTES) {
            "kiva.auth.jwtSecret (KIVA_JWT_SECRET) must be at least $MIN_SECRET_BYTES bytes, was ${signingKey.size}. $HOW_TO_MAKE_ONE"
        }
        require(!pepper.contentEquals(signingKey)) {
            "KIVA_OTP_PEPPER and KIVA_JWT_SECRET must be different secrets. $HOW_TO_MAKE_ONE"
        }
        require(maxSmsPerHour > 0) { "kiva.auth.maxSmsPerHour must be positive, was $maxSmsPerHour" }
    }

    // Never the secrets themselves, nor a whole phone, if this object is ever printed.
    override fun toString() =
        "AuthConfig(otpPepper=<hidden>, jwtSecret=<hidden>, maxSmsPerHour=$maxSmsPerHour, bootstrapAdminPhone=${bootstrapAdminPhone?.masked()})"

    companion object {
        const val MIN_SECRET_BYTES = 32
        private const val HOW_TO_MAKE_ONE = "Make one with `openssl rand -base64 32` and set it as an environment variable."

        /**
         * From the config values. The secrets are base64 and required (there are no defaults to fall back to); the
         * bootstrap phone is optional, but one that isn't a mobile number stops the boot rather than being skipped.
         */
        fun of(otpPepperBase64: String?, jwtSecretBase64: String?, maxSmsPerHour: Int, bootstrapAdminPhoneRaw: String? = null): AuthConfig =
            AuthConfig(
                otpPepper = secretOf(otpPepperBase64, "kiva.auth.otpPepper (KIVA_OTP_PEPPER)"),
                jwtSecret = secretOf(jwtSecretBase64, "kiva.auth.jwtSecret (KIVA_JWT_SECRET)"),
                maxSmsPerHour = maxSmsPerHour,
                bootstrapAdminPhone = bootstrapAdminPhoneRaw?.takeUnless { it.isBlank() }?.let { raw ->
                    IranianMobileNumber.makeNew(raw).getOrNull()
                        ?: throw IllegalArgumentException("kiva.auth.bootstrapAdminPhone (KIVA_BOOTSTRAP_ADMIN_PHONE) is not a mobile number")
                },
            )

        private fun secretOf(base64: String?, name: String): ByteArray {
            require(!base64.isNullOrBlank()) { "$name is not set. $HOW_TO_MAKE_ONE" }
            return try {
                Base64.getDecoder().decode(base64.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$name is not base64. $HOW_TO_MAKE_ONE", e)
            }
        }
    }
}

/** Where SMS go. Only [SmsProvider.LOG] until a real provider is chosen, before launch. */
data class SmsConfig(val provider: SmsProvider)

enum class SmsProvider {
    /** Writes codes to the log instead of sending them. Development only. */
    LOG,
    ;

    companion object {
        /** From the config value (`log`). An unknown name stops the boot rather than quietly sending nothing. */
        fun of(raw: String): SmsProvider =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("kiva.sms.provider '$raw' is not known; it can be: ${entries.joinToString { it.name.lowercase() }}")
    }
}

/**
 * The shop's own address. Its rules (shipping costs, free shipping, ...) are in the database, not here: see ShopSettings.
 *
 * @property siteUrl the shop's own address (`https://kiva.ir`), for the canonical and share links of pages.
 */
data class ShopConfig(val siteUrl: String) {
    companion object {
        val DEFAULT = ShopConfig(siteUrl = "https://kiva.ir")
    }
}

/**
 * @property allowedOrigins the frontends a browser may call the API from (CORS), e.g. `https://kiva.ir`.
 *   Each is a full origin: scheme, host and port when not the default, no path, no trailing slash.
 * @property trustedProxyHops 1 behind the shop's own nginx, 0 anywhere else. Only with 1 is the client's address taken
 *   from X-Forwarded-For (its last entry, the one nginx wrote); with 0 that header is ignored, because anyone can send
 *   one and get past the limit per address.
 */
data class HttpConfig(val allowedOrigins: List<String>, val trustedProxyHops: Int = 0) {
    init {
        allowedOrigins.forEach { origin ->
            require(ORIGIN.matches(origin)) { "kiva.http.allowedOrigins: '$origin' is not an origin like https://kiva.ir" }
        }
        require(trustedProxyHops in 0..1) { "kiva.http.trustedProxyHops is 0, or 1 behind our nginx; was $trustedProxyHops" }
    }

    companion object {
        private val ORIGIN = Regex("^https?://[^/*\\s]+$")

        /** From the comma-separated config value; empty pieces are ignored. */
        fun of(commaSeparated: String, trustedProxyHops: Int = 0) =
            HttpConfig(commaSeparated.split(',').map { it.trim() }.filter { it.isNotEmpty() }, trustedProxyHops)
    }
}

/** @property enabled run DevCatalogSeeder on startup. Local development only. */
data class SeedConfig(val enabled: Boolean)

data class DatasourceConfig(
    val url: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int,
) {
    // Keep the password out of logs if this object is ever printed.
    override fun toString() = "DatasourceConfig(url=$url, username=$username, maxPoolSize=$maxPoolSize)"
}
