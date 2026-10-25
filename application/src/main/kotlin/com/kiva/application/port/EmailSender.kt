package com.kiva.application.port

import com.kiva.domain.aggregate.user.model.valueobject.Email

/**
 * Sends an email (roadmap phase 13, decision 11): plain text, never HTML. Development and a server without a provider
 * write only to the log; SMTP sends for real.
 *
 * Throws when the server refuses or can't be reached. It is called after the work is committed, never inside a
 * transaction (a slow server must not hold a database connection), and a failure is only a warning for the caller.
 */
interface EmailSender {
    suspend fun send(to: Email, subject: String, textBody: String)
}
