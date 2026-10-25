package com.kiva.infrastructure.email

import com.kiva.domain.aggregate.user.model.valueobject.Email
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The email senders (roadmap step 13.1): the log one shows only a masked address, and the SMTP one speaks to a server
 * (a few lines of SMTP on a local socket here) with the subject and the text in UTF-8.
 */
class EmailSendersTest {

    private val sara = (Email.makeNew("Sara@Example.com") as com.kiva.common.railway.Result.Success).value

    @Test
    fun `an address is masked to its first letter and domain`() {
        assertEquals("s***@example.com", LogEmailSender.masked(sara))
    }

    @Test
    fun `log - the masked address and the subject are written, never the whole address or the text`() {
        val logger = LoggerFactory.getLogger(LogEmailSender::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runBlocking { LogEmailSender().send(sara, "پاسخ پیام CT-482113", "متن محرمانهٔ پاسخ") }
        } finally {
            logger.detachAppender(appender)
        }

        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { "s***@example.com" in it && "پاسخ پیام CT-482113" in it }, "$lines")
        assertTrue(lines.none { "sara@example.com" in it || "محرمانه" in it }, "$lines")
    }

    @Test
    fun `smtp - the mail reaches the server with its Persian subject and text, from the sender to the address`() {
        val server = try {
            ServerSocket(0)
        } catch (e: Exception) {
            assumeTrue(false, "no free local port for the test SMTP server: ${e.message}")
            return
        }
        val received = CompletableFuture<String>()
        val envelope = mutableListOf<String>()
        thread(isDaemon = true) {
            server.use { listening ->
                listening.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    fun reply(line: String) = writer.print("$line\r\n").also { writer.flush() }
                    reply("220 test")
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("EHLO") || line.startsWith("HELO") -> reply("250 test")
                            line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> envelope += line.also { reply("250 ok") }
                            line == "DATA" -> {
                                reply("354 go")
                                val data = StringBuilder()
                                while (true) {
                                    val dataLine = reader.readLine() ?: break
                                    if (dataLine == ".") break
                                    data.append(dataLine).append("\r\n")
                                }
                                received.complete(data.toString())
                                reply("250 queued")
                            }
                            line == "QUIT" -> reply("221 bye").also { return@use }
                            else -> reply("250 ok")
                        }
                    }
                }
            }
        }
        val sender = SmtpEmailSender(SmtpConfig("127.0.0.1", server.localPort, username = null, password = null, from = "noreply@kivashop.ir", startTls = false))

        runBlocking { sender.send(sara, "پاسخ پیام CT-482113", "سلام سارا،\nپاسخ پشتیبانی کیوا اینجاست.") }

        val raw = received.get()
        val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
        assertEquals("پاسخ پیام CT-482113", message.subject)
        assertEquals("سلام سارا،\nپاسخ پشتیبانی کیوا اینجاست.", (message.content as String).replace("\r\n", "\n").trimEnd())
        assertTrue(envelope.any { it.contains("noreply@kivashop.ir") } && envelope.any { it.contains("sara@example.com") }, "$envelope")
    }
}
