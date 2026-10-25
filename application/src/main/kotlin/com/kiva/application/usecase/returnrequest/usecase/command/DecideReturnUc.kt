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
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * Staff decide on a return the customer asked for (`POST /admin/returns/{returnId}/decision`; ADMIN, OPERATOR; roadmap
 * section 12.2, decision 8). Only from REQUESTED (422 INVALID_STATUS_TRANSITION otherwise):
 *
 * - **approve**: APPROVED; who pays the way back as the reason suggested unless [Cmd.shippingPaidBy] says otherwise, and
 *   staff's own [Cmd.instructions] or none (the default text for the payer is shown). The order stays RETURN_REQUESTED.
 * - **reject**: REJECTED with a reason of 3 to 500 (400 REJECTION_REASON_REQUIRED); the order goes back to DELIVERED
 *   (OrderReturnEnded REJECTED), and while its window is open the customer may ask again.
 *
 * What doesn't belong to the decision taken is ignored (the reason of an approval, the payer of a rejection). The shape
 * is checked first, every problem at once. Then the return's order and the return are locked (ReturnByIdForPanel: locks
 * 9, 10). One RETURN_DECIDED row in the change log; after the commit «مرجوعی تأیید شد» or «مرجوعی رد شد», always (the
 * reason never in it). A panel form: never tried again.
 */
class DecideReturnUc(
    private val transactionManager: TransactionManager,
    private val returnByIdForPanel: ReturnByIdForPanel,
    private val returnRequestRepository: ReturnRequestRepository,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val returnNotices: ReturnNotices,
    private val clock: Clock,
) : CommandUseCase<DecideReturnUc.Cmd, Unit, DecideReturnUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; staff's texts never go into a log ([toString]). */
    data class Cmd(
        val returnId: String,
        val approve: Boolean?,
        val shippingPaidBy: String?,
        val instructions: String?,
        val rejectionReason: String?,
        val actor: PanelActor,
    ) {
        override fun toString(): String = "Cmd(returnId=$returnId, approve=$approve, shippingPaidBy=$shippingPaidBy, actor=${actor.userId.value})"
    }

    private sealed class Decision {
        data class Approve(val payer: ReturnShippingPayer?, val instructions: ReturnText?) : Decision()
        data class Reject(val reason: ReturnText) : Decision()
    }

    /** What to tell the customer after the commit. */
    private data class Decided(val approved: Boolean, val code: ReturnCode, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReturnId.makeNew(command.returnId).getOrNull()
        logger.info("DecideReturnUc.execute returnId=${id?.value ?: "(not an id)"} approve=${command.approve} actor=${command.actor.userId.value}")
        val decision = when (val checked = decisionOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (id == null) return Result.failure(Failure.ReturnNotFound)

        val done = transactionManager.inTransaction {
            val (ret, order) = returnByIdForPanel.lock(id) ?: return@inTransaction Result.failure(Failure.ReturnNotFound)
            val now = clock.instant()
            val before = ret.status
            val payerBefore = ret.shippingPaidBy
            val moved = when (decision) {
                is Decision.Approve -> ret.approve(decision.payer, decision.instructions, command.actor.userId, now)
                is Decision.Reject -> ret.reject(decision.reason, command.actor.userId, now)
            }
            if (moved is Result.Failure) return@inTransaction Result.failure(Failure.InvalidTransition(moved.failure.meta))
            if (decision is Decision.Reject) {
                order.endReturn(ret.id, ReturnOutcome.REJECTED, command.actor.userId, now)
                orderRepository.save(order)
            }
            returnRequestRepository.save(ret)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.RETURN_DECIDED, AuditEntityType.RETURN, ret.id.value.toString(), ret.orderCode.value,
                    AuditDiff.build {
                        field("status", before, ret.status)
                        field("shippingPaidBy", payerBefore, ret.shippingPaidBy)
                    },
                ),
            )
            logger.info("DecideReturnUc: return ${ret.id.value} of order ${ret.orderCode.value} is ${ret.status}")
            Result.success(Decided(ret.status == ReturnStatus.APPROVED, ret.code, userRepository.findById(ret.userId)?.phone))
        }
        return when (done) {
            is Result.Success -> {
                val decided = done.value
                if (decided.approved) returnNotices.approved(decided.to, decided.code) else returnNotices.rejected(decided.to, decided.code)
                Result.success(Unit)
            }
            is Result.Failure -> done
        }
    }

    /** The body's shape, every problem at once: only the fields of the decision taken are read. */
    private fun decisionOf(command: Cmd): Result<Decision, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val decision = when (command.approve) {
            null -> null.also { problems += FieldFailure("approve", FieldRequired) }
            true -> {
                val payer = command.shippingPaidBy?.trim()?.ifEmpty { null }?.let { raw ->
                    when (val parsed = ReturnShippingPayer.makeNew(raw)) {
                        is Result.Success -> parsed.value
                        is Result.Failure -> null.also { problems += parsed.failure }
                    }
                }
                val instructions = when (val parsed = ReturnText.makeNew(command.instructions).forField("instructions")) {
                    is Result.Success -> parsed.value
                    is Result.Failure -> null.also { problems += parsed.failure }
                }
                Decision.Approve(payer, instructions)
            }
            false -> when (val parsed = ReturnText.makeReason(command.rejectionReason, ReturnText.Failure.RejectionReasonRequired).forField("rejectionReason")) {
                is Result.Success -> Decision.Reject(parsed.value)
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }
        return if (problems.isEmpty()) Result.success(checkNotNull(decision)) else Result.failure(Failure.InvalidInput(problems))
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Every problem of the body, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReturnNotFound : Failure("RETURN_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not REQUESTED (422), with `meta.from`, `to` and `allowed`. */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)
    }
}
