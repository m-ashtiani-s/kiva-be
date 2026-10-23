package com.kiva.domain.aggregate.contact

import com.kiva.common.patch.Patch
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.contact.entity.ContactMessage
import com.kiva.domain.aggregate.contact.model.ContactMessageChange
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ReplyChannel
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The panel's change of a contact message (roadmap phase 13, decision 9): its shape, and what it does to the message. */
class ContactMessageChangeTest {

    private val now = Instant.parse("2026-10-23T08:00:00Z")
    private val operator = UserId.constructFromPersisted(31)

    private val message = ContactMessage.create(
        TicketCode.ofNumber(482113),
        (ContactMessageDraft.makeNew(ContactMessageDraft.Input("OTHER", "سارا محمدی", "09123334455", null, null, "سلام، یه سؤال داشتم.")) as Result.Success).value,
        null,
        now,
    )

    private fun input(status: String? = null, reply: String? = null, channel: String? = null, note: Patch<String?> = Patch.Unchanged) =
        ContactMessageChange.Input(status, reply, channel, note)

    private fun change(input: ContactMessageChange.Input) = (ContactMessageChange.makeNew(input) as Result.Success).value

    private fun problems(input: ContactMessageChange.Input) = (ContactMessageChange.makeNew(input) as Result.Failure).failure.causes.map { it.field to it.code }

    @Test
    fun `nothing sent, or the same status and note, changes nothing`() {
        assertNull(message.updated(change(input()), operator, now))
        assertNull(message.updated(change(input(status = "OPEN", note = Patch.Set(null))), operator, now))
        assertNull(message.updated(change(input(reply = "   ", note = Patch.Set("  "))), operator, now))
    }

    @Test
    fun `a reply is kept with its channel, when and by whom - a second one replaces it and changes the message again`() {
        val later = now.plus(Duration.ofMinutes(5))
        val first = assertNotNull(message.updated(change(input(reply = "سلام\r\nعکس‌ها امروز می‌رن.", channel = "SMS")), operator, later))

        assertEquals(listOf("سلام\nعکس‌ها امروز می‌رن.", ReplyChannel.SMS, later, operator, later), listOf(first.reply, first.replyChannel, first.repliedAt, first.repliedBy, first.updatedAt))
        assertEquals(ContactStatus.OPEN, first.status)
        val again = assertNotNull(first.updated(change(input(reply = "سلام\r\nعکس‌ها امروز می‌رن.", channel = "SMS")), operator, later.plusSeconds(60)))
        assertEquals(later.plusSeconds(60), again.repliedAt)
    }

    @Test
    fun `any status to any, the note replaced and cleared, the reply left as it was`() {
        val closed = assertNotNull(message.updated(change(input(status = "CLOSED", note = Patch.Set("زنگ زدم"))), operator, now))
        assertEquals(ContactStatus.CLOSED, closed.updated(change(input(note = Patch.Set("دوباره زنگ زدم"))), operator, now)?.status, "a status not sent stays")
        val open = assertNotNull(closed.updated(change(input(status = "OPEN")), operator, now))
        val cleared = assertNotNull(open.updated(change(input(note = Patch.Set(null))), operator, now))

        assertEquals(listOf(ContactStatus.CLOSED, ContactStatus.OPEN), listOf(closed.status, open.status))
        assertEquals(listOf("زنگ زدم", "زنگ زدم", null), listOf(closed.internalNote, open.internalNote, cleared.internalNote))
        assertNull(cleared.reply)
    }

    @Test
    fun `a reply and its channel together - every problem at once - and nothing of the texts in toString`() {
        assertEquals(listOf("replyChannel" to "REPLY_CHANNEL_REQUIRED"), problems(input(reply = "سلام")))
        assertEquals(listOf("reply" to "REPLY_REQUIRED"), problems(input(channel = "EMAIL")))
        assertEquals(
            listOf("status" to "CONTACT_STATUS_INVALID", "reply" to "TEXT_TOO_SHORT", "replyChannel" to "REPLY_CHANNEL_INVALID", "internalNote" to "TEXT_TOO_LONG"),
            problems(input(status = "DONE", reply = "س", channel = "PIGEON", note = Patch.Set("ن".repeat(1001)))),
        )
        assertEquals(listOf("reply" to "TEXT_TOO_LONG", "internalNote" to "TEXT_INVALID"), problems(input(reply = "س".repeat(1001), channel = "SMS", note = Patch.Set("یادداشت\u0007"))))
        val printed = change(input(reply = "متن محرمانه", channel = "EMAIL", note = Patch.Set("یادداشت محرمانه"))).toString() +
            input(reply = "متن محرمانه", note = Patch.Set("یادداشت محرمانه")).toString()
        assertTrue("محرمانه" !in printed, printed)
    }
}
