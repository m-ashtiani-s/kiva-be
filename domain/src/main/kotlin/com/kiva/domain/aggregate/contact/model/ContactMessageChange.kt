package com.kiva.domain.aggregate.contact.model

import com.kiva.common.patch.Patch
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ReplyChannel
import com.kiva.domain.aggregate.review.model.valueobject.TextTooShort

/**
 * What the panel changes on a contact message (`PATCH /admin/contact-messages/{ticketCode}`; roadmap phase 13,
 * decision 9), checked on its own shape, every problem at once. Every field optional: `{}` changes nothing.
 *
 * - [status] any of the three, from any (no state machine); only when sent;
 * - [reply] its text (2 to 1000, plain, new lines kept) and its channel come together: one without the other is
 *   REPLY_CHANNEL_REQUIRED or REPLY_REQUIRED. A text that is only spaces is not sent;
 * - [internalNote] at most 1000, replaced; null or only spaces clears it. Never reaches the customer.
 *
 * Whether the message has an email for an EMAIL reply is the use case's (it reads the message).
 */
@ConsistentCopyVisibility
data class ContactMessageChange private constructor(
    val status: ContactStatus?,
    val reply: Reply?,
    val internalNote: Patch<String?>,
) {
    /** A reply and how it goes. [toString] hides its text. */
    data class Reply(val text: String, val channel: ReplyChannel) {
        override fun toString(): String = "Reply($channel)"
    }

    override fun toString(): String = "ContactMessageChange(status=$status, reply=$reply, note=${internalNote != Patch.Unchanged})"

    /** The body as it came. Texts: [toString] hides them. */
    data class Input(val status: String?, val reply: String?, val replyChannel: String?, val internalNote: Patch<String?>) {
        override fun toString(): String = "Input(status=$status, replyChannel=$replyChannel, reply=${reply != null}, note=${internalNote != Patch.Unchanged})"
    }

    companion object {
        const val MIN_REPLY = 2
        const val MAX_REPLY = 1000
        const val MAX_NOTE = 1000

        fun makeNew(input: Input): Result<ContactMessageChange, InputProblems> {
            val problems = Problems()
            val status = problems.take(statusOf(input.status).forField("status"))
            val text = problems.take(replyTextOf(input.reply).forField("reply"))
            val channel = problems.take(channelOf(input.replyChannel).forField("replyChannel"))
            val note = problems.take(noteOf(input.internalNote).forField("internalNote"))
            val textSent = plain(input.reply) != null
            val channelSent = input.replyChannel?.trim()?.isNotEmpty() == true
            if (textSent && !channelSent) problems.add(FieldFailure("replyChannel", Problem.ReplyChannelRequired))
            if (channelSent && !textSent) problems.add(FieldFailure("reply", Problem.ReplyRequired))
            if (problems.any()) return Result.failure(problems.all())
            val reply = text.value?.let { Reply(it, checkNotNull(channel.value)) }
            return Result.success(ContactMessageChange(status.value, reply, note.value))
        }

        private fun statusOf(raw: String?): Result<ContactStatus?, ResultFailure> {
            if (raw == null) return Result.success(null)
            return ContactStatus.of(raw)?.let { Result.success(it) } ?: Result.failure(Problem.StatusInvalid)
        }

        private fun channelOf(raw: String?): Result<ReplyChannel?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return ReplyChannel.of(text)?.let { Result.success(it) } ?: Result.failure(Problem.ReplyChannelInvalid)
        }

        private fun replyTextOf(raw: String?): Result<String?, ResultFailure> {
            val text = plain(raw) ?: return Result.success(null)
            return when {
                text.any { it.isISOControl() && it != '\n' } -> Result.failure(TextInvalid)
                text.length < MIN_REPLY -> Result.failure(TextTooShort(MIN_REPLY))
                text.length > MAX_REPLY -> Result.failure(TextTooLong(MAX_REPLY))
                else -> Result.success(text)
            }
        }

        private fun noteOf(patch: Patch<String?>): Result<Patch<String?>, ResultFailure> {
            if (patch !is Patch.Set) return Result.success(Patch.Unchanged)
            val text = plain(patch.value) ?: return Result.success(Patch.Set(null))
            return when {
                text.any { it.isISOControl() && it != '\n' } -> Result.failure(TextInvalid)
                text.length > MAX_NOTE -> Result.failure(TextTooLong(MAX_NOTE))
                else -> Result.success(Patch.Set(text))
            }
        }

        /** Each line's letters made one form (new lines kept), the ends trimmed; null when nothing is left. */
        private fun plain(raw: String?): String? =
            raw?.replace("\r\n", "\n")?.lines()?.joinToString("\n") { it.normalizePersianLetters() }?.trim()?.ifEmpty { null }
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object StatusInvalid : Problem("CONTACT_STATUS_INVALID")
        data object ReplyChannelInvalid : Problem("REPLY_CHANNEL_INVALID")
        data object ReplyChannelRequired : Problem("REPLY_CHANNEL_REQUIRED")
        data object ReplyRequired : Problem("REPLY_REQUIRED")

        /** An EMAIL reply to a message without an email (the use case's, on `replyChannel`). */
        data object EmailNotAvailable : Problem("EMAIL_NOT_AVAILABLE")
    }
}
