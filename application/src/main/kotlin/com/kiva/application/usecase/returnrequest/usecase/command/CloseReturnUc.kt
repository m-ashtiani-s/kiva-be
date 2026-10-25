package com.kiva.application.usecase.returnrequest.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.returnrequest.ReturnByIdForPanel
import com.kiva.application.usecase.returnrequest.ReturnNotices
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * Staff close a return without money back (`POST /admin/returns/{returnId}/close`; ADMIN, OPERATOR; roadmap section
 * 12.2, decision 18): the parcel never came, or the check found it wasn't the shop's fault. CLOSED with a reason of 3 to
 * 500 the customer reads (400 CLOSE_REASON_REQUIRED), and the order DELIVERED again, never returned again
 * (OrderReturnEnded CLOSED). From APPROVED, PICKUP_SCHEDULED or RECEIVED (REQUESTED is turned down instead; 422
 * INVALID_STATUS_TRANSITION otherwise), and only while no refund of it is PENDING or PROCESSING (422 RETURN_REFUND_OPEN):
 * money on its way would belong to a closed return. What went back on the shelf stays there.
 *
 * Under the return's order and the return (ReturnByIdForPanel: locks 9, 10); the order's refunds are read under the
 * order's lock, which every refund's change takes first. One RETURN_CLOSED row in the change log; after the commit
 * «مرجوعی بسته شد», only with [Cmd.notifyCustomer] (by default), never the reason. A panel form: never tried again.
 */
class CloseReturnUc(
    private val transactionManager: TransactionManager,
    private val returnByIdForPanel: ReturnByIdForPanel,
    private val returnRequestRepository: ReturnRequestRepository,
    private val refundRepository: RefundRepository,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val returnNotices: ReturnNotices,
    private val clock: Clock,
) : CommandUseCase<CloseReturnUc.Cmd, Unit, CloseReturnUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; the reason never goes into a log ([toString]). */
    data class Cmd(val returnId: String, val reason: String?, val notifyCustomer: Boolean?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(returnId=$returnId, notifyCustomer=$notifyCustomer, actor=${actor.userId.value})"
    }

    /** What to tell the customer after the commit; null when they shouldn't be told. */
    private data class Closed(val code: ReturnCode, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReturnId.makeNew(command.returnId).getOrNull()
        logger.info("CloseReturnUc.execute returnId=${id?.value ?: "(not an id)"} notifyCustomer=${command.notifyCustomer} actor=${command.actor.userId.value}")
        val reason = when (val parsed = ReturnText.makeReason(command.reason, ReturnText.Failure.CloseReasonRequired).forField("reason")) {
            is Result.Success -> parsed.value
            is Result.Failure -> return Result.failure(Failure.InvalidInput(listOf(parsed.failure)))
        }
        if (id == null) return Result.failure(Failure.ReturnNotFound)

        val done = transactionManager.inTransaction {
            val (ret, order) = returnByIdForPanel.lock(id) ?: return@inTransaction Result.failure(Failure.ReturnNotFound)
            val now = clock.instant()
            val before = ret.status
            val refundOpen = refundRepository.findAllOf(ret.orderId).any {
                it.returnId == ret.id.value && (it.status == RefundStatus.PENDING || it.status == RefundStatus.PROCESSING)
            }
            when (val closed = ret.close(reason, refundOpen, now)) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(failureOf(closed.failure))
            }
            order.endReturn(ret.id, ReturnOutcome.CLOSED, command.actor.userId, now)
            orderRepository.save(order)
            returnRequestRepository.save(ret)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.RETURN_CLOSED, AuditEntityType.RETURN, ret.id.value.toString(), ret.orderCode.value,
                    AuditDiff.build { field("status", before, ret.status) },
                ),
            )
            logger.info("CloseReturnUc: return ${ret.id.value} of order ${ret.orderCode.value} closed from $before")
            if (command.notifyCustomer == false) return@inTransaction Result.success(null)
            Result.success(Closed(ret.code, userRepository.findById(ret.userId)?.phone))
        }
        return when (done) {
            is Result.Success -> {
                done.value?.let { returnNotices.closed(it.to, it.code) }
                Result.success(Unit)
            }
            is Result.Failure -> Result.failure(done.failure)
        }
    }

    private fun failureOf(failure: ReturnRequest.CloseFailure): Failure = when (failure) {
        is ReturnRequest.CloseFailure.InvalidTransition -> Failure.InvalidTransition(failure.meta)
        ReturnRequest.CloseFailure.RefundOpen -> Failure.RefundOpen
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** The reason is missing, too short or too long (400 on `reason`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReturnNotFound : Failure("RETURN_NOT_FOUND", FailureKind.NOT_FOUND)

        /** From REQUESTED or a final status (422), with `meta.from`, `to` and `allowed`. */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)

        /** A refund of it is PENDING or PROCESSING: finish it or fail it first (422). */
        data object RefundOpen : Failure("RETURN_REFUND_OPEN", FailureKind.BUSINESS_RULE)
    }
}
