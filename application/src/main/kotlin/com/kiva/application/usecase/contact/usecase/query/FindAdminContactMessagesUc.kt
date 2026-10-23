package com.kiva.application.usecase.contact.usecase.query

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.AdminContactMessageDto
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.contact.model.ContactMessageChange
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.repository.ContactMessageRepository
import com.kiva.domain.aggregate.user.repository.UserRepository

/**
 * The panel's contact messages (`GET /admin/contact-messages`; ADMIN, SUPPORT; roadmap phase 13, decision 18): every
 * message, or one status, one topic; newest first. An unknown status or topic is 400 on its parameter. Each the whole
 * message, with who replied last.
 */
class FindAdminContactMessagesUc(
    private val transactionManager: TransactionManager,
    private val contactMessageRepository: ContactMessageRepository,
    private val userRepository: UserRepository,
) : QueryUseCase<FindAdminContactMessagesUc.Qry, Page<AdminContactMessageDto>, FindAdminContactMessagesUc.Failure> {

    private val logger = getLogger()

    /** The query parameters as they came. */
    data class Qry(val pageable: Pageable, val status: String? = null, val topic: String? = null)

    override suspend fun execute(query: Qry): Result<Page<AdminContactMessageDto>, Failure> {
        logger.info("FindAdminContactMessagesUc.execute status=${query.status} topic=${query.topic} page=${query.pageable.page}")
        val problems = mutableListOf<ResultFailure>()
        val status = query.status?.trim()?.ifEmpty { null }?.let { raw ->
            ContactStatus.of(raw) ?: null.also { problems += FieldFailure("status", ContactMessageChange.Problem.StatusInvalid) }
        }
        val topic = query.topic?.trim()?.ifEmpty { null }?.let { raw ->
            ContactTopic.of(raw) ?: null.also { problems += FieldFailure("topic", ContactMessageDraft.TopicInvalid) }
        }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))

        return transactionManager.inTransaction {
            val page = contactMessageRepository.page(ContactMessageRepository.Filter(status, topic), query.pageable)
            val operators = page.items.mapNotNull { it.repliedBy }.distinct().associateWith(userRepository::findById)
            Result.success(page.map { message -> AdminContactMessageDto(message, message.repliedBy?.let { ActorNameDto.of(it, operators[it]) }) })
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
