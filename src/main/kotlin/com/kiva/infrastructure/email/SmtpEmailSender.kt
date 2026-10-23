package com.kiva.infrastructure.email

import com.kiva.application.port.EmailSender
import com.kiva.common.logging.getLogger
import com.kiva.domain.aggregate.user.model.valueobject.Email
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * Sends through an SMTP server (`kiva.email.provider = smtp`; Jakarta Mail, Eclipse Angus): plain text in UTF-8,
 * STARTTLS required unless turned off, 10 seconds to connect, read and write. Blocking, so on Dispatchers.IO. Throws
 * when the server refuses or can't be reached; the caller only warns.
 */
class SmtpEmailSender(private val config: SmtpConfig) : EmailSender {

    private val logger = getLogger()

    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", (config.username != null).toString())
            put("mail.smtp.starttls.enable", config.startTls.toString())
            put("mail.smtp.starttls.required", config.startTls.toString())
            put("mail.smtp.connectiontimeout", TIMEOUT_MS)
            put("mail.smtp.timeout", TIMEOUT_MS)
            put("mail.smtp.writetimeout", TIMEOUT_MS)
        },
    )

    override suspend fun send(to: Email, subject: String, textBody: String) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from, true))
            setRecipient(Message.RecipientType.TO, InternetAddress(to.value, true))
            setSubject(subject, Charsets.UTF_8.name())
            setText(textBody, Charsets.UTF_8.name())
        }
        withContext(Dispatchers.IO) {
            if (config.username == null) Transport.send(message) else Transport.send(message, config.username, config.password)
        }
        logger.info("Email to ${LogEmailSender.masked(to)} sent")
    }

    private companion object {
        const val TIMEOUT_MS = "10000"
    }
}
