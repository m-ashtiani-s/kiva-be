package com.kiva.application.usecase.contact.usecase.command

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.AdminContactMessageDto
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.EmailSender
import com.kiva.application.port.PanelActor
import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.contact.EmailTexts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.contact.entity.ContactMessage
import com.kiva.domain.aggregate.contact.model.ContactMessageChange
import com.kiva.domain.aggregate.contact.model.valueobject.ReplyChannel
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import com.kiva.domain.aggregate.contact.repository.ContactMessageRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Support changes a contact message (`PATCH /admin/contact-messages/{ticketCode}`; ADMIN, SUPPORT; roadmap section
 * 18.6), in the contract's order:
 *
 * 1. the shape, every problem at once (400; ContactMessageChange);
 * 2. in one transaction: the message, held (404 NOT_FOUND; a code that can't be one too); an EMAIL reply to a message
 *    without an email is 400 EMAIL_NOT_AVAILABLE on `replyChannel`; then the change. Nothing changed (`{}`, the same
 *    status and note): no save, no change log row. Else one CONTACT_MESSAGE_UPDATED row, without the reply's text or
 *    the note's (decision 20).
 * 3. after the commit, when there is a reply: SMS only says there is one, with the code (decision 7; its text never goes
 *    by SMS); EMAIL carries its whole text (decision 8). A failure is only a WARN: the reply is stored.
 *
 * Answers the message as the panel sees it now.
 */
class UpdateContactMessageUc(
    private val transactionManager: TransactionManager,
    private val contactMessageRepository: ContactMessageRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val smsSender: SmsSender,
    private val emailSender: EmailSender,
    private val clock: Clock,
) : CommandUseCase<UpdateContactMessageUc.Cmd, AdminContactMessageDto, UpdateContactMessageUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val ticketCode: String, val input: ContactMessageChange.Input, val actor: PanelActor) {
        override fun toString(): String = "Cmd(ticketCode=$ticketCode, $input, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<AdminContactMessageDto, Failure> {
        logger.info("UpdateContactMessageUc.execute ticketCode=${command.ticketCode} ${command.input} by=${command.actor.userId.value}")
        val change = ContactMessageChange.makeNew(command.input).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val code = TicketCode.of(command.ticketCode) ?: return Result.failure(Failure.NotFound)

        val saved = transactionManager.inTransaction {
            val now = clock.instant()
            val message = contactMessageRepository.lockFind(code) ?: return@inTransaction Result.failure(Failure.NotFound)
            if (change.reply?.channel == ReplyChannel.EMAIL && message.email == null) {
                return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("replyChannel", ContactMessageChange.Problem.EmailNotAvailable))))
            }
            val updated = message.updated(change, command.actor.userId, now)
            if (updated != null) {
                contactMessageRepository.save(updated)
                auditTrail.record(
                    AuditEntry(now, command.actor, AuditAction.CONTACT_MESSAGE_UPDATED, AuditEntityType.CONTACT_MESSAGE, code.value, code.value, changesOf(message, updated)),
                )
                logger.info("UpdateContactMessageUc: ${code.value} updated, status=${updated.status}, replied=${change.reply?.channel}")
            }
            val current = updated ?: message
            Result.success(AdminContactMessageDto(current, current.repliedBy?.let { ActorNameDto.of(it, userRepository.findById(it)) }))
        }.unwrapOr { return Result.failure(it) }

        change.reply?.let { send(saved.message, it) }
        return Result.success(saved)
    }

    /** Decision 20: the status, that a reply went and how, that the note changed; never the reply's or the note's text. */
    private fun changesOf(before: ContactMessage, after: ContactMessage) = AuditDiff.build {
        field("status", before.status, after.status)
        if (after.repliedAt != before.repliedAt) {
            field("replied", null, true)
            field("replyChannel", null, after.replyChannel)
        }
        longText("internalNote", before.internalNote, after.internalNote)
    }

    private suspend fun send(message: ContactMessage, reply: ContactMessageChange.Reply) {
        try {
            when (reply.channel) {
                ReplyChannel.SMS -> smsSender.sendContactReplied(message.phone, message.ticketCode)
                ReplyChannel.EMAIL -> emailSender.send(
                    checkNotNull(message.email),
                    EmailTexts.contactReplySubject(message.ticketCode),
                    EmailTexts.contactReplyBody(message.fullName, message.ticketCode, reply.text),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The reply is stored; the panel can send it again. Only the error's kind: a mail server's words can hold the address.
            logger.warn("UpdateContactMessageUc: the ${reply.channel} reply to ${message.ticketCode.value} could not be sent (${e::class.simpleName})")
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** Every problem of the body, each under its field, or EMAIL_NOT_AVAILABLE on `replyChannel` (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
