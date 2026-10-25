package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.dto.AdminNoteDto
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.ordernote.entity.OrderNote
import com.kiva.domain.aggregate.ordernote.entity.OrderNoteText
import com.kiva.domain.aggregate.ordernote.repository.OrderNoteRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * An internal note on an order (`POST /admin/orders/{orderCode}/notes`; ADMIN, OPERATOR): only the panel sees it, and
 * it changes nothing of the order. One ORDER_NOTE_ADDED row in the change log, without the note's text.
 *
 * The answer is the note as the order's page lists it (its id and its author by name), as AdjustInventoryUc answers
 * with its row: a note has no page of its own to read it back from.
 */
class AddOrderNoteUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderNoteRepository: OrderNoteRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<AddOrderNoteUc.Cmd, AdminNoteDto, AddOrderNoteUc.Failure> {

    private val logger = getLogger()

    /** @property orderCode the path's code, as it came; @property text the body's, as it came. */
    data class Cmd(val orderCode: String, val text: String?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(orderCode=$orderCode, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<AdminNoteDto, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("AddOrderNoteUc.execute orderCode=${code?.value ?: "(not a code)"} actor=${command.actor.userId.value}")
        val text = OrderNoteText.makeNew(command.text).unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val order = orderByCode.findForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val now = clock.instant()
            val note = orderNoteRepository.add(OrderNote.write(order.orderId, text, command.actor.userId, now))
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.ORDER_NOTE_ADDED, AuditEntityType.ORDER, order.code.value, order.code.value, null))
            Result.success(AdminNoteDto.from(note, userRepository.findById(command.actor.userId)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** The text is missing or too long (400 on `text`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
