package com.kiva.domain.aggregate.contact

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.domain.aggregate.contact.model.policy.ContactPolicy
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.random.RandomGenerator
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The contact form's shape, its codes and its limit per mobile (roadmap phase 13, decisions 2 to 4). */
class ContactMessageDraftTest {

    private val valid = ContactMessageDraft.Input(
        topic = "ORDER_FOLLOWUP", fullName = "سارا محمدی", phone = "09123456789", email = null, orderCode = null,
        message = "سلام، سفارشم هنوز عکسش برام ارسال نشده.",
    )

    private fun draft(input: ContactMessageDraft.Input) = (ContactMessageDraft.makeNew(input) as Result.Success).value

    private fun problems(input: ContactMessageDraft.Input): List<Pair<String?, String>> =
        (ContactMessageDraft.makeNew(input) as Result.Failure).failure.causes.map { it.field to it.code }

    @Test
    fun `phone, order code and email are made one form - Persian digits, kv- and capitals - and the letters of the name too`() {
        val made = draft(valid.copy(fullName = " سارا  كريمي ", phone = "۰۹۱۲ ۳۴۵ ۶۷۸۹", email = " Sara@Example.com ", orderCode = "kv-۲۱۸۳۴۰"))

        assertEquals("09123456789", made.phone.value)
        assertEquals("KV-218340", made.orderCode?.value)
        assertEquals("sara@example.com", made.email?.value)
        assertEquals("سارا کریمی", made.fullName)
    }

    @Test
    fun `empty email and order code are not sent, and the message keeps its new lines`() {
        val made = draft(valid.copy(email = "  ", orderCode = "", message = "سلام\r\nسفارش من کجاست؟\n\nممنون"))

        assertNull(made.email)
        assertNull(made.orderCode)
        assertEquals("سلام\nسفارش من کجاست؟\n\nممنون", made.message)
    }

    @Test
    fun `every problem at once, each under its field`() {
        val found = problems(ContactMessageDraft.Input(topic = "COMPLAINT", fullName = "سا", phone = "12345", email = "sara@", orderCode = "218340", message = "سلام خوبی"))

        assertEquals(
            listOf("topic" to "CONTACT_TOPIC_INVALID", "fullName" to "TEXT_TOO_SHORT", "phone" to "PHONE_INVALID", "email" to "EMAIL_INVALID", "orderCode" to "ORDER_CODE_INVALID", "message" to "TEXT_TOO_SHORT"),
            found,
        )
        assertEquals(
            listOf("topic" to "FIELD_REQUIRED", "fullName" to "FIELD_REQUIRED", "phone" to "PHONE_REQUIRED", "message" to "FIELD_REQUIRED"),
            problems(ContactMessageDraft.Input(null, " ", null, null, null, null)),
        )
    }

    @Test
    fun `the limits of length, and a control character other than a new line`() {
        assertEquals(listOf("fullName" to "TEXT_TOO_LONG"), problems(valid.copy(fullName = "س".repeat(81))))
        assertEquals(listOf("message" to "TEXT_TOO_LONG"), problems(valid.copy(message = "س".repeat(601))))
        assertEquals(listOf("message" to "TEXT_INVALID"), problems(valid.copy(message = "سلام\u0007سفارشم کجاست؟")))
        assertEquals("س".repeat(600), draft(valid.copy(message = "س".repeat(600))).message)
        // A tab is only space, like a run of spaces folded into one.
        assertEquals("سلام سفارشم کجاست؟", draft(valid.copy(message = "  سلام\tسفارشم    کجاست؟ \n")).message)
    }

    @Test
    fun `nothing personal in toString`() {
        val printed = draft(valid.copy(email = "sara@example.com")).toString() + valid.toString()

        listOf("سارا", "09123456789", "sara@example.com", "سفارشم").forEach { assertTrue(it !in printed, "$it in $printed") }
    }

    @Test
    fun `codes - CT- and six digits, typed in any case and digits, and the topics that show the order field`() {
        assertEquals("CT-004213", TicketCode.ofNumber(4213).value)
        assertEquals("CT-482113", TicketCode.of(" ct-۴۸۲۱۱۳ ")?.value)
        assertNull(TicketCode.of("CT-48211"))
        assertNull(TicketCode.of("KV-482113"))
        val drawn = TicketCodeGenerator(fixed(999_999, 7)).let { listOf(it.next(), it.next()).map { code -> code.value } }
        assertEquals(listOf("CT-999999", "CT-000007"), drawn)
        assertEquals(setOf(ContactTopic.ORDER_FOLLOWUP, ContactTopic.RETURN), ContactTopic.entries.filter { it.showsOrderCode }.toSet())
    }

    @Test
    fun `five from a mobile in a day - the sixth waits until the oldest of the five is a day old`() {
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val times = listOf(20L, 15, 10, 5, 1).map { now.minus(Duration.ofHours(it)) }

        assertNull(ContactPolicy.phoneLimit(times.drop(1), now))
        assertEquals(Duration.ofHours(4), ContactPolicy.phoneLimit(times, now)?.retryAfter)
        assertEquals(4L * 3600, ContactPolicy.phoneLimit(times, now)?.meta?.get("retryAfterSeconds"))
        // Six already (two sent at once before the limit held): the wait is until only four are left in the day.
        assertEquals(Duration.ofHours(9), ContactPolicy.phoneLimit(times + now.minus(Duration.ofHours(3)), now)?.retryAfter)
    }

    private fun fixed(vararg numbers: Int): RandomGenerator = object : RandomGenerator {
        private val next = numbers.iterator()

        override fun nextLong(): Long = error("not used")

        override fun nextInt(bound: Int): Int = next.next()
    }
}
