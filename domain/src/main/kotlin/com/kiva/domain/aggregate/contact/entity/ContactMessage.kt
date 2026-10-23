package com.kiva.domain.aggregate.contact.entity

import com.kiva.common.patch.applyTo
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.contact.model.ContactMessageChange
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.model.valueobject.ReplyChannel
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Email
import java.time.Instant

/**
 * A message of the «تماس با ما» form (roadmap phase 13): the customer's words and how to reach them, and support's
 * status, internal note and reply. Kept as a row, by its [ticketCode].
 *
 * Personal data (name, phone, email, text): [toString] says none of it.
 *
 * @property repliedBy the operator who wrote the last reply; [reply], [replyChannel], [repliedAt] and it come together.
 * @property userId the customer's account, when they were logged in.
 */
class ContactMessage private constructor(
    val ticketCode: TicketCode,
    val topic: ContactTopic,
    val fullName: String,
    val phone: IranianMobileNumber,
    val email: Email?,
    val orderCode: OrderCode?,
    val message: String,
    val status: ContactStatus,
    val reply: String?,
    val replyChannel: ReplyChannel?,
    val repliedAt: Instant?,
    val repliedBy: UserId?,
    val internalNote: String?,
    val userId: UserId?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(listOf(reply, replyChannel, repliedAt, repliedBy).all { it == null } || listOf(reply, replyChannel, repliedAt, repliedBy).none { it == null }) {
            "Contact message ${ticketCode.value} has half a reply"
        }
        require(replyChannel != ReplyChannel.EMAIL || email != null) { "Contact message ${ticketCode.value} was replied to by email without one" }
    }

    override fun toString(): String = "ContactMessage(${ticketCode.value}, $topic, $status)"

    /**
     * The message after [change] by [by] (decision 9), or null when it would stay as it is: no save, no change log row.
     * The status changes only when sent; the note is replaced (or cleared); a reply always changes it, replacing the
     * last one, at [now]: it is sent again.
     */
    fun updated(change: ContactMessageChange, by: UserId, now: Instant): ContactMessage? {
        val status = change.status ?: this.status
        val note = change.internalNote.applyTo(internalNote)
        val reply = change.reply
        if (status == this.status && note == internalNote && reply == null) return null
        return ContactMessage(
            ticketCode, topic, fullName, phone, email, orderCode, message, status,
            reply = reply?.text ?: this.reply,
            replyChannel = reply?.channel ?: replyChannel,
            repliedAt = if (reply != null) now else repliedAt,
            repliedBy = if (reply != null) by else repliedBy,
            internalNote = note,
            userId = userId,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    companion object {
        /** A new message, OPEN (decision 2). */
        fun create(ticketCode: TicketCode, draft: ContactMessageDraft, userId: UserId?, now: Instant): ContactMessage = ContactMessage(
            ticketCode, draft.topic, draft.fullName, draft.phone, draft.email, draft.orderCode, draft.message, ContactStatus.OPEN,
            reply = null, replyChannel = null, repliedAt = null, repliedBy = null, internalNote = null, userId = userId, createdAt = now, updatedAt = now,
        )

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            ticketCode: TicketCode,
            topic: ContactTopic,
            fullName: String,
            phone: IranianMobileNumber,
            email: Email?,
            orderCode: OrderCode?,
            message: String,
            status: ContactStatus,
            reply: String?,
            replyChannel: ReplyChannel?,
            repliedAt: Instant?,
            repliedBy: UserId?,
            internalNote: String?,
            userId: UserId?,
            createdAt: Instant,
            updatedAt: Instant,
        ): ContactMessage = ContactMessage(
            ticketCode, topic, fullName, phone, email, orderCode, message, status, reply, replyChannel, repliedAt, repliedBy, internalNote, userId,
            createdAt, updatedAt,
        )
    }
}
