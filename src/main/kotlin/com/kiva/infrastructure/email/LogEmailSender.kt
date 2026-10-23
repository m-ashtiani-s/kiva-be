package com.kiva.infrastructure.email

import com.kiva.application.port.EmailSender
import com.kiva.common.logging.getLogger
import com.kiva.domain.aggregate.user.model.valueobject.Email

/**
 * `kiva.email.provider = log` (the default): writes to whom (masked) and the subject to the log, never the text, and sends
 * nothing. The app works without an email provider; a reply by email or a welcome mail is then only in the log.
 */
class LogEmailSender : EmailSender {

    private val logger = getLogger()

    init {
        logger.warn("Email provider is 'log': emails are not sent, only logged without their text.")
    }

    override suspend fun send(to: Email, subject: String, textBody: String) {
        logger.warn("Email to ${masked(to)}: «$subject» (not sent: provider is log)")
    }

    companion object {
        /** `sara@example.com` as `s***@example.com`: enough to tell two apart in a log, not to write to one. */
        fun masked(email: Email): String {
            val (local, domain) = email.value.split('@', limit = 2)
            return "${local.take(1)}***@$domain"
        }
    }
}
