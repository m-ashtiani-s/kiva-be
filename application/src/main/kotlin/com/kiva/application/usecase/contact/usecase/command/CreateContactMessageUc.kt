package com.kiva.application.usecase.contact.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.contact.TicketCodeGenerator
import com.kiva.domain.aggregate.contact.entity.ContactMessage
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.domain.aggregate.contact.model.policy.ContactPolicy
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import com.kiva.domain.aggregate.contact.repository.ContactMessageRepository
import java.time.Clock
import java.time.Instant

/**
 * Someone sends the «تماس با ما» form (`POST /contact/messages`; a login optional; roadmap section 18.6):
 *
 * 1. the shape, every problem at once (400; ContactMessageDraft);
 * 2. in one transaction, this mobile's messages held: five in the last 24 hours already (from any address) is 429 with
 *    Retry-After, until the oldest of them is a day old; then a fresh `CT-` code, drawn again when taken (at most five
 *    tries, then an error), and the message stored OPEN, with the customer's account when they are logged in.
 *
 * The limit per address is the route's (RateLimit `contact`). Nobody is told (decision 6): the panel's queue shows it.
 * The log has the code and the topic, never the name, the phone, the email or the text.
 */
class CreateContactMessageUc(
    private val transactionManager: TransactionManager,
    private val contactMessageRepository: ContactMessageRepository,
    private val ticketCodeGenerator: TicketCodeGenerator,
    private val clock: Clock,
) : CommandUseCase<CreateContactMessageUc.Cmd, CreateContactMessageUc.Created, CreateContactMessageUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; personal fields never go into a log ([toString]). */
    data class Cmd(val userId: UserId?, val input: ContactMessageDraft.Input) {
        override fun toString(): String = "Cmd(userId=${userId?.value}, $input)"
    }

    data class Created(val ticketCode: TicketCode, val createdAt: Instant)

    override suspend fun execute(command: Cmd): Result<Created, Failure> {
        logger.info("CreateContactMessageUc.execute topic=${command.input.topic} loggedIn=${command.userId != null}")
        val draft = ContactMessageDraft.makeNew(command.input).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val now = clock.instant()
            contactMessageRepository.lockPhone(draft.phone)
            ContactPolicy.phoneLimit(contactMessageRepository.createdSince(draft.phone, now.minus(ContactPolicy.PHONE_WINDOW)), now)?.let {
                logger.info("CreateContactMessageUc: limit per mobile reached for ${draft.phone.masked()}")
                return@inTransaction Result.failure(Failure.RateLimited(it))
            }
            repeat(TicketCodeGenerator.MAX_TRIES) {
                val message = ContactMessage.create(ticketCodeGenerator.next(), draft, command.userId, now)
                if (contactMessageRepository.addIfCodeFree(message)) {
                    logger.info("CreateContactMessageUc: ${message.ticketCode.value} (${draft.topic}) written")
                    return@inTransaction Result.success(Created(message.ticketCode, now))
                }
            }
            error("No free ticket code in ${TicketCodeGenerator.MAX_TRIES} tries")
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Every problem of the form, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** Five from this mobile in a day already (429, with `meta.retryAfterSeconds`). */
        data class RateLimited(val refusal: ContactPolicy.RateLimited) : Failure(refusal.code, refusal.kind, meta = refusal.meta)
    }
}
