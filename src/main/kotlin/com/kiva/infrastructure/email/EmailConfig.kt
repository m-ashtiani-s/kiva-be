package com.kiva.infrastructure.email

import io.ktor.server.config.ApplicationConfig

/** Where emails go: the log by default, an SMTP server when one is set up. [smtp] only with [EmailProvider.SMTP]. */
data class EmailConfig(val provider: EmailProvider, val smtp: SmtpConfig? = null) {
    init {
        require((provider == EmailProvider.SMTP) == (smtp != null)) { "SMTP's settings go with kiva.email.provider = smtp, and only with it" }
    }

    companion object {
        val LOG = EmailConfig(EmailProvider.LOG)

        /** SMTP's submission port, with STARTTLS. */
        const val DEFAULT_PORT = 587

        /** The provider, and with `smtp` its settings: without its host (`KIVA_SMTP_HOST`) or sender the app doesn't start. */
        fun from(config: ApplicationConfig): EmailConfig {
            val provider = EmailProvider.of(config.propertyOrNull("kiva.email.provider")?.getString() ?: "log")
            if (provider != EmailProvider.SMTP) return EmailConfig(provider)
            fun optional(name: String) = config.propertyOrNull("kiva.email.smtp.$name")?.getString()?.trim()?.takeUnless { it.isEmpty() }
            return EmailConfig(
                provider,
                SmtpConfig(
                    host = requireNotNull(optional("host")) { "kiva.email.provider is smtp, but KIVA_SMTP_HOST is not set" },
                    port = optional("port")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("KIVA_SMTP_PORT must be a port number, not '$it'") } ?: DEFAULT_PORT,
                    username = optional("username"),
                    password = optional("password"),
                    from = requireNotNull(optional("from")) { "kiva.email.provider is smtp, but KIVA_SMTP_FROM is not set" },
                    // Strict: a typo stops the boot instead of quietly sending in the clear.
                    startTls = (optional("startTls") ?: "true").toBooleanStrict(),
                ),
            )
        }
    }
}

/**
 * An SMTP server (decision 11): STARTTLS unless turned off, a login when [username] is given.
 *
 * @property from the sender as the mail shows it (`کیوا <noreply@kivashop.ir>` or a bare address).
 * @property password a secret: never in git, never in a log ([toString] hides it).
 */
data class SmtpConfig(val host: String, val port: Int, val username: String?, val password: String?, val from: String, val startTls: Boolean) {
    init {
        require(port in 1..65535) { "KIVA_SMTP_PORT must be a port number, not $port" }
        require((username == null) == (password == null)) { "KIVA_SMTP_USERNAME and KIVA_SMTP_PASSWORD go together" }
    }

    override fun toString(): String = "SmtpConfig(host=$host, port=$port, username=$username, password=${password?.let { "***" }}, from=$from, startTls=$startTls)"
}

enum class EmailProvider {
    /** Writes who and what about to the log; sends nothing. The default until a provider is chosen. */
    LOG,

    /** An SMTP server (`kiva.email.smtp`). */
    SMTP,
    ;

    companion object {
        /** From the config value (`log`). An unknown name stops the boot rather than quietly sending nothing. */
        fun of(raw: String): EmailProvider =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("kiva.email.provider '$raw' is not known; it can be: ${entries.joinToString { it.name.lowercase() }}")
    }
}
