package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.EmailSender
import com.kiva.domain.aggregate.user.model.valueobject.Email
import java.util.Collections

/** Remembers what it sent; fails when told to, like a mail server that is down. */
class RecordingEmailSender : EmailSender {
    val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

    data class Sent(val to: Email, val subject: String, val textBody: String)

    @Volatile
    var down = false

    override suspend fun send(to: Email, subject: String, textBody: String) {
        check(!down) { "the mail server is down" }
        sent += Sent(to, subject, textBody)
    }
}
