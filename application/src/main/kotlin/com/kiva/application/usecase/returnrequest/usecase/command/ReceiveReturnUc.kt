package com.kiva.application.usecase.returnrequest.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.returnrequest.ReturnByIdForPanel
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.forField
import com.kiva.common.railway.requiredIn
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReceiptLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import java.time.Clock

/**
 * A return's parcel reached the warehouse and was checked (`POST /admin/returns/{returnId}/receipt`; ADMIN, OPERATOR;
 * roadmap section 12.2, decisions 11 and 12): RECEIVED with how many of each line came back and how many of those go
 * back on the shelf, and the order RETURNED (OrderReturnReceived). From APPROVED or PICKUP_SCHEDULED (422
 * INVALID_STATUS_TRANSITION otherwise).
 *
 * The shape first, every problem at once: 1 to 20 lines, each a line number and two numbers of 0 to 20, and a note of at
 * most 1000 (internal). Then, under the return's order and the return (ReturnByIdForPanel: locks 9, 10): the receipt has
 * each line of the return exactly once (400 RECEIPT_ITEMS_MISMATCH with `meta.expectedItemIds`), never more back than
 * went (RECEIVED_QUANTITY_EXCEEDS) nor more on the shelf than came (RESTOCK_EXCEEDS_RECEIVED). What goes back on the
 * shelf is a change of the stock by hand, reason RETURNED, noted with the return's code and done by this member of staff
 * (InventoryDomainService.restockForReturn, lock 7 in order): an OPERATOR may do it, though the inventory form is not
 * theirs, because the permission is this endpoint's. `soldCount` stays: a returned order still counts as bought.
 *
 * One RETURN_RECEIVED row in the change log (no INVENTORY_ADJUSTED of its own: the stock's history has the rows). No
 * SMS: what the check found is internal. The stock changes, so a change of a stock at the same moment makes the whole
 * transaction run again, up to [MAX_ATTEMPTS] times.
 */
class ReceiveReturnUc(
    private val transactionManager: TransactionManager,
    private val returnByIdForPanel: ReturnByIdForPanel,
    private val returnRequestRepository: ReturnRequestRepository,
    private val orderRepository: OrderRepository,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<ReceiveReturnUc.Cmd, Unit, ReceiveReturnUc.Failure> {

    private val logger = getLogger()

    /** One line of the body, as it came. */
    data class LineFields(val orderItemId: Int?, val receivedQuantity: Int?, val restockQuantity: Int?)

    /** The body as it came; the note never goes into a log ([toString]). */
    data class Cmd(val returnId: String, val items: List<LineFields>?, val note: String?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(returnId=$returnId, items=${items?.size}, actor=${actor.userId.value})"
    }

    private data class Input(val lines: List<ReceiptLine>, val note: ReturnText?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReturnId.makeNew(command.returnId).getOrNull()
        logger.info("ReceiveReturnUc.execute returnId=${id?.value ?: "(not an id)"} items=${command.items?.size} actor=${command.actor.userId.value}")
        val input = when (val checked = inputOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (id == null) return Result.failure(Failure.ReturnNotFound)

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return receiveOnce(id, input, command.actor)
            } catch (e: ConcurrentChangeException) {
                logger.info("ReceiveReturnUc: return ${id.value} or its stock changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        return receiveOnce(id, input, command.actor)
    }

    private suspend fun receiveOnce(id: ReturnId, input: Input, actor: PanelActor): Result<Unit, Failure> = transactionManager.inTransaction {
        val (ret, order) = returnByIdForPanel.lock(id) ?: return@inTransaction Result.failure(Failure.ReturnNotFound)
        val now = clock.instant()
        val before = ret.status
        when (val received = ret.receive(input.lines, input.note, actor.userId, now)) {
            is Result.Success -> {}
            is Result.Failure -> return@inTransaction Result.failure(failureOf(received.failure))
        }

        val lines = order.lines.associateBy { it.lineId }
        val restock = input.lines.filter { it.restock > 0 }.map { line ->
            val orderLine = lines[line.lineId] ?: error("Return ${ret.id.value} of line ${line.lineId}, which order ${order.code.value} doesn't have")
            InventoryDomainService.Restock(orderLine.productId, orderLine.variantId, line.restock)
        }
        if (restock.isNotEmpty()) inventoryDomainService.restockForReturn(ret.code, restock, actor.userId, now).forEach(inventoryRepository::save)
        order.receiveReturn(ret.id, actor.userId, now)
        orderRepository.save(order)
        returnRequestRepository.save(ret)
        auditTrail.record(
            AuditEntry(
                now, actor, AuditAction.RETURN_RECEIVED, AuditEntityType.RETURN, ret.id.value.toString(), ret.orderCode.value,
                AuditDiff.build {
                    field("status", before, ret.status)
                    list("items", null, input.lines.sortedBy { it.lineId }.map { mapOf("orderItemId" to it.lineId, "receivedQuantity" to it.received, "restockQuantity" to it.restock) })
                },
            ),
        )
        logger.info("ReceiveReturnUc: return ${ret.id.value} of order ${ret.orderCode.value} received, ${restock.sumOf { it.quantity }} back on the shelf")
        Result.success(Unit)
    }

    /** The body's shape, every problem at once, each under its field. */
    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val raw = command.items.orEmpty()
        val lines = when {
            raw.isEmpty() -> null.also { problems += FieldFailure("items", FieldRequired) }
            raw.size > ReturnPolicy.MAX_ITEMS -> null.also { problems += FieldFailure("items", TooManyItems(ReturnPolicy.MAX_ITEMS)) }
            else -> raw.mapIndexedNotNull { i, item ->
                val lineId = when {
                    item.orderItemId == null -> null.also { problems += FieldFailure("items.$i.orderItemId", FieldRequired) }
                    item.orderItemId < 1 -> null.also { problems += FieldFailure("items.$i.orderItemId", ReturnPolicy.Problem.UnknownLine) }
                    else -> item.orderItemId
                }
                val received = when (val checked = item.receivedQuantity.requiredIn(0..ReturnPolicy.MAX_QUANTITY).forField("items.$i.receivedQuantity")) {
                    is Result.Success -> checked.value
                    is Result.Failure -> null.also { problems += checked.failure }
                }
                val restock = when (val checked = item.restockQuantity.requiredIn(0..ReturnPolicy.MAX_QUANTITY).forField("items.$i.restockQuantity")) {
                    is Result.Success -> checked.value
                    is Result.Failure -> null.also { problems += checked.failure }
                }
                if (lineId != null && received != null && restock != null) ReceiptLine.constructFromPersisted(lineId, received, restock) else null
            }
        }
        val note = when (val parsed = ReturnText.makeNew(command.note).forField("note")) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        return if (problems.isEmpty()) Result.success(Input(checkNotNull(lines), note)) else Result.failure(Failure.InvalidInput(problems))
    }

    private fun failureOf(failure: ReturnRequest.ReceiveFailure): Failure = when (failure) {
        is ReturnRequest.ReceiveFailure.InvalidTransition -> Failure.InvalidTransition(failure.meta)
        is ReturnRequest.ReceiveFailure.ItemsMismatch -> Failure.InvalidInput(listOf(failure))
        is ReturnRequest.ReceiveFailure.ItemsInvalid -> Failure.InvalidInput(failure.problems)
        is ReturnRequest.ReceiveFailure.ReceivedExceeds, ReturnRequest.ReceiveFailure.RestockExceedsReceived ->
            error("A line's failure comes inside ItemsInvalid, never alone: $failure")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Every problem of the body or of the receipt, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReturnNotFound : Failure("RETURN_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not APPROVED nor PICKUP_SCHEDULED (422), with `meta.from`, `to` and `allowed`. */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)
    }
}
