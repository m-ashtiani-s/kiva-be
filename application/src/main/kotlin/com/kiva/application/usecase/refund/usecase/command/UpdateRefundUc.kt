package com.kiva.application.usecase.refund.usecase.command

import com.kiva.application.dto.RefundDto
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.order.OrderNotices
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.BankReference
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundNote
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Instant

/**
 * Follows a refund (`PATCH /admin/refunds/{refundId}`; ADMIN only): staff are on it (PROCESSING), the money went back
 * (COMPLETED, with the bank's reference, always), or it couldn't be done (FAILED). COMPLETED and FAILED are final (422
 * INVALID_STATUS_TRANSITION); the note is the customer's to see.
 *
 * A completed refund is also a fact of its order (OrderRefunded): `totals.refunded` and its timeline. The order's row is
 * locked first (lock 9) and the refund's after, the order every refund change takes, so a refund opened and one followed
 * on the same order at once never wait on each other in a circle. One REFUND_UPDATED row in the change log, with the
 * status from and to, and only the name of `referenceId`; after the commit «پولت برگشت» to the buyer when completed.
 * Asking for PROCESSING while it is PROCESSING changes nothing and logs nothing.
 */
class UpdateRefundUc(
    private val transactionManager: TransactionManager,
    private val refundRepository: RefundRepository,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val orderNotices: OrderNotices,
    private val clock: Clock,
) : CommandUseCase<UpdateRefundUc.Cmd, RefundDto, UpdateRefundUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [referenceId] and [note] never go into a log ([toString]). */
    data class Cmd(val refundId: String, val status: String?, val referenceId: String?, val note: String?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(refundId=$refundId, status=$status, actor=${actor.userId.value})"
    }

    private data class Input(val status: RefundStatus, val referenceId: BankReference?, val note: RefundNote?)

    private data class Done(val refund: RefundDto, val completed: Boolean, val code: OrderCode, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<RefundDto, Failure> {
        val id = RefundId.makeNew(command.refundId).getOrNull()
        logger.info("UpdateRefundUc.execute refundId=${id?.value ?: "(not an id)"} status=${command.status} actor=${command.actor.userId.value}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (id == null) return Result.failure(Failure.RefundNotFound)
        val checked = (input as Result.Success).value

        val done = transactionManager.inTransaction {
            // Lock 9 first: the order's row, found through the refund read without a lock.
            val orderId = refundRepository.findOrderOf(id) ?: return@inTransaction Result.failure(Failure.RefundNotFound)
            orderQmRepository.lockOrder(orderId) ?: error("The order ${orderId.value} of refund ${id.value} is not in the read model")
            val refund = refundRepository.lock(id) ?: return@inTransaction Result.failure(Failure.RefundNotFound)
            val now = clock.instant()
            val before = refund.status
            when (val moved = move(refund, checked, now)) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(moved.failure)
            }
            val order = orderRepository.get(refund.orderId) ?: error("The order ${refund.orderId.value} of refund ${id.value} has no events")
            if (refund.status == before) return@inTransaction Result.success(Done(RefundDto.from(refund), completed = false, order.code, to = null))

            if (refund.status == RefundStatus.COMPLETED) {
                order.recordRefund(refund.id, refund.amount, command.actor.userId, now)
                orderRepository.save(order)
            }
            refundRepository.save(refund)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.REFUND_UPDATED, AuditEntityType.REFUND, refund.id.value.toString(), order.code.value,
                    AuditDiff.build {
                        field("status", before, refund.status)
                        hidden("referenceId", null, refund.referenceId?.value)
                    },
                ),
            )
            val completed = refund.status == RefundStatus.COMPLETED
            Result.success(Done(RefundDto.from(refund), completed, order.code, if (completed) userRepository.findById(order.userId)?.phone else null))
        }
        return when (done) {
            is Result.Success -> {
                if (done.value.completed) orderNotices.refundCompleted(done.value.to, done.value.code)
                Result.success(done.value.refund)
            }
            is Result.Failure -> done
        }
    }

    private fun move(refund: Refund, input: Input, now: Instant): Result<Refund, Failure> = when (input.status) {
        RefundStatus.PROCESSING -> refund.markProcessing(input.note, now).orInvalidTransition()
        RefundStatus.FAILED -> refund.fail(input.note, now).orInvalidTransition()
        RefundStatus.COMPLETED -> when (val failure = refund.complete(input.referenceId, input.note, now).failureOrNull()) {
            null -> Result.success(refund)
            is Refund.CompleteFailure.NotOpen -> Result.failure(Failure.InvalidTransition(failure.meta))
            Refund.CompleteFailure.ReferenceRequired -> Result.failure(Failure.InvalidInput(listOf(FieldFailure("referenceId", Problem.ReferenceRequired))))
        }
        RefundStatus.PENDING -> error("A refund is never asked to go back to PENDING")
    }

    private fun Result<Refund, Refund.InvalidTransition>.orInvalidTransition(): Result<Refund, Failure> = when (this) {
        is Result.Success -> this
        is Result.Failure -> Result.failure(Failure.InvalidTransition(failure.meta))
    }

    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val status = when (val parsed = RefundStatus.makeNewTarget(command.status?.trim().orEmpty())) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val referenceId = when (val parsed = BankReference.makeNew(command.referenceId)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val note = when (val parsed = RefundNote.makeNew(command.note)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        return if (problems.isEmpty()) Result.success(Input(status!!, referenceId, note)) else Result.failure(Failure.InvalidInput(problems))
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object ReferenceRequired : Problem("REFERENCE_ID_REQUIRED")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object RefundNotFound : Failure("REFUND_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Completed or failed already (422). */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)
    }
}
