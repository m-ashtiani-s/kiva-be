package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.repository.ResolutionDecision
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderCancellation
import com.kiva.application.usecase.order.OrderItems
import com.kiva.application.usecase.order.OrderNotices
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.model.policy.OrderTransitionPolicy
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.refund.RefundDomainService
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.repository.RefundIdGenerator
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Instant

/**
 * Staff resolve the customer's change request (`POST /admin/orders/{orderCode}/change-request/resolution`; ADMIN,
 * OPERATOR; roadmap section 8.5): turned down (REJECT, with a note for the customer) or carried out (APPLY). Either way
 * the order goes back to PROCESSING and its photos to WAITING, for a new batch.
 *
 * Carried out, by the request's type:
 * - COLOR or MODEL: the line takes the color asked for (or [Cmd.variantId], staff's choice), copied from the catalog with
 *   its price of today; the order's hold of the old color goes back and the new one is held (one order of locks across
 *   both); what it costs more is owed, what it costs less is first taken off what is owed and the rest refunded;
 * - CANCEL_ITEM: the line goes, its hold goes back, and what was paid for it (ChangeRequestPolicy.paidShareOf) is settled
 *   the same way; the only line of an order is the whole order cancelled, exactly as the panel cancels
 *   (OrderCancellation), with «لغو شد» instead of «درخواستت بررسی شد»;
 * - OTHER: done by hand; only the status comes back, with a note of what was done.
 *
 * A refund made here is PENDING, BANK_TRANSFER, of source CHANGE_REQUEST, and never more than the order's money still
 * allows. One ORDER_CHANGE_RESOLVED row in the change log. With `notifyCustomer` (by default) the customer gets
 * «درخواستت بررسی شد» after the commit, never with the note (no free text in an SMS).
 *
 * Locks in section 4.11's order: the order's payments (6; one being confirmed is 422 PAYMENT_IN_PROGRESS: a balance
 * would be paid into a change), its row (9), its refunds, the stock (7), its stream (8). The only line's CANCEL_ITEM goes
 * through OrderCancellation, which takes the code's use (5) first. A change of the order at the same moment makes the
 * whole transaction run again, up to [MAX_ATTEMPTS] times, deciding again.
 */
class ResolveChangeRequestUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderCancellation: OrderCancellation,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val offerQmRepository: OfferQmRepository,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val refundRepository: RefundRepository,
    private val refundIdGenerator: RefundIdGenerator,
    private val refundDomainService: RefundDomainService,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val orderNotices: OrderNotices,
    private val clock: Clock,
) : CommandUseCase<ResolveChangeRequestUc.Cmd, Unit, ResolveChangeRequestUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [note] is for the customer, never logged ([toString]). */
    data class Cmd(val orderCode: String, val decision: String?, val variantId: Long?, val note: String?, val notifyCustomer: Boolean?, val actor: PanelActor) {
        override fun toString(): String =
            "Cmd(orderCode=$orderCode, decision=$decision, variantId=$variantId, notifyCustomer=$notifyCustomer, actor=${actor.userId.value})"
    }

    private data class Input(val decision: ResolutionDecision, val variantId: VariantId?, val note: StatusNote?, val notify: Boolean)

    /** What to tell the customer after the commit: «لغو شد» when the change cancelled the order, «درخواستت بررسی شد» otherwise. */
    private data class Resolved(val code: OrderCode, val cancelled: Boolean, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("ResolveChangeRequestUc.execute orderCode=${code?.value ?: "(not a code)"} decision=${command.decision} variantId=${command.variantId} actor=${command.actor.userId.value}")
        val input = when (val checked = inputOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (code == null) return Result.failure(Failure.OrderNotFound)

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return resolveOnce(command, input)
            } catch (e: ConcurrentChangeException) {
                logger.info("ResolveChangeRequestUc: order ${code.value} changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        return resolveOnce(command, input)
    }

    private suspend fun resolveOnce(command: Cmd, input: Input): Result<Unit, Failure> {
        val resolved = transactionManager.inTransaction {
            val read = orderByCode.findForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val now = clock.instant()
            // Read before any lock, only to tell the one case that cancels the order: it must take the code's lock (5) first.
            val open = (orderRepository.get(read.orderId) ?: error("Order ${read.orderId.value} of the read model has no events")).takeIf { it.isOnlyLineTakenOut(input) }
            if (open != null) {
                cancelWhole(read, checkNotNull(open.openChange), input, command.actor, now)
            } else {
                resolve(read, input, command.actor, now)
            }
        }
        return when (resolved) {
            is Result.Success -> {
                if (input.notify) {
                    val done = resolved.value
                    if (done.cancelled) orderNotices.cancelled(done.to, done.code) else orderNotices.changeResolved(done.to, done.code)
                }
                Result.success(Unit)
            }
            is Result.Failure -> resolved
        }
    }

    /** The change carried out is a CANCEL_ITEM of the order's only line: the whole order cancelled. */
    private fun Order.isOnlyLineTakenOut(input: Input): Boolean =
        input.decision == ResolutionDecision.APPLY && openChange?.type == ChangeType.CANCEL_ITEM && lines.size == 1

    /** The only line taken out: the panel's cancellation, answering that very request (OrderCancellation checks it under its locks). */
    private fun cancelWhole(read: OrderQm, change: ChangeRequest, input: Input, actor: PanelActor, now: Instant): Result<Resolved, Failure> {
        if (input.variantId != null) return Result.failure(Failure.InvalidInput(listOf(FieldFailure("variantId", Problem.VariantNotAllowed))))
        when (val cancelled = orderCancellation.run(read.orderId, CancelledBy.STAFF, null, input.note, actor.userId, now, answering = change)) {
            is Result.Success -> {}
            is Result.Failure -> return Result.failure(Failure.Refused(cancelled.failure.code, cancelled.failure.meta))
        }
        audit(read.code, input.decision, change.type, from = null, to = null, actor, now)
        logger.info("ResolveChangeRequestUc: the only line of order ${read.code.value} taken out; the order is cancelled")
        return Result.success(Resolved(read.code, cancelled = true, userRepository.findById(read.userId)?.phone))
    }

    private fun resolve(read: OrderQm, input: Input, actor: PanelActor, now: Instant): Result<Resolved, Failure> {
        val payments = paymentRepository.lockAttemptsOf(read.orderId)
        if (payments.any { it.status == PaymentStatus.VERIFYING }) return Result.failure(Failure.Refused("PAYMENT_IN_PROGRESS", emptyMap()))
        orderQmRepository.lockOrder(read.orderId) ?: error("Order ${read.orderId.value} is not in the read model")
        val order = orderRepository.get(read.orderId) ?: error("Order ${read.orderId.value} of the read model has no events")
        val change = order.openChange
        if (order.status != OrderStatus.CHANGE_REQUESTED || change == null) return Result.failure(Failure.InvalidTransition(order.status))
        // Decided on what was read before the locks; the order, locked, is a cancellation now: decide again.
        if (order.isOnlyLineTakenOut(input)) throw ConcurrentChangeException(OrderDomainEvent.AGGREGATE_NAME, EventAggregateId.of(read.orderId.value))

        val line = change.lineId?.let { lineId -> order.lines.first { it.lineId == lineId } }
        val settlement = when (input.decision) {
            ResolutionDecision.REJECT -> {
                order.rejectChange(input.note, actor.userId, now).failureOrNull()?.let { return Result.failure(Failure.of(it)) }
                null
            }
            ResolutionDecision.APPLY -> when (change.type) {
                ChangeType.OTHER, ChangeType.CANCEL_ITEM -> {
                    if (input.variantId != null) return Result.failure(Failure.InvalidInput(listOf(FieldFailure("variantId", Problem.VariantNotAllowed))))
                    if (change.type == ChangeType.CANCEL_ITEM) takeOut(order, actor, now)
                    order.applyChange(input.note, actor.userId, now).unwrapOr { return Result.failure(Failure.of(it)) }
                }
                ChangeType.COLOR, ChangeType.MODEL -> {
                    swap(order, change, checkNotNull(line), input, actor, now)?.let { return Result.failure(it) }
                    order.applyChange(input.note, actor.userId, now).unwrapOr { return Result.failure(Failure.of(it)) }
                }
            }
        }
        orderRepository.save(order)
        val refund = settlement?.refund?.takeIf { it > Money.ZERO }?.let { refundOf(order, payments, it, actor, now) }
        // The color that took the line's place, if one did.
        val swapped = input.decision == ResolutionDecision.APPLY && change.type.swapsColor
        val putIn = if (swapped) order.lines.first { it.lineId == checkNotNull(line).lineId }.variantId else null
        audit(read.code, input.decision, change.type, from = line?.variantId?.takeIf { swapped }, to = putIn, actor, now)
        logger.info(
            "ResolveChangeRequestUc: the ${change.type} change of order ${read.code.value} ${input.decision}: owes ${order.balanceDue.toman}, refund ${refund?.id?.value ?: "none"}",
        )
        return Result.success(Resolved(read.code, cancelled = false, userRepository.findById(read.userId)?.phone))
    }

    /** CANCEL_ITEM of one of several lines: it goes, and so does its hold on the stock. */
    private fun takeOut(order: Order, actor: PanelActor, now: Instant) {
        val before = order.lines
        order.removeLine(actor.userId, now).failureOrNull()?.let { error("Order ${order.code.value} is CHANGE_REQUESTED, yet its line wasn't taken out: ${it.code}") }
        // Only holds go back: a line taken out never lacks stock.
        inventoryDomainService.rebalanceForOrder(order.code, before, order.lines, actor.userId, now)
            .unwrapOr { error("Taking a line of order ${order.code.value} out needed stock: ${it.code}") }
            .forEach(inventoryRepository::save)
    }

    /**
     * COLOR or MODEL: the line takes the color staff chose or the customer asked for, as the catalog has it now. A color
     * nobody has or that doesn't fit the change is 400, one not in the shop 422, too many of its product 422, too few
     * left 422 with what is left; nothing is saved then.
     */
    private fun swap(order: Order, change: ChangeRequest, line: OrderLine, input: Input, actor: PanelActor, now: Instant): Failure? {
        val wanted = input.variantId ?: checkNotNull(change.desiredVariantId) { "A ${change.type} change without its color" }
        val qm = offerQmRepository.findOffers(listOf(wanted))[wanted]
            ?: return Failure.InvalidInput(listOf(FieldFailure("variantId", Problem.VariantInvalid)))
        if (!qm.offer.inShop) return Failure.Refused("PRODUCT_UNAVAILABLE", emptyMap())
        val offer = qm.offer
        val before = order.lines
        val newLine = line.replacedBy(offer.productId, wanted, OrderItems.snapshotOf(qm), offer.priceTag, offer.reservable, offer.returnable)
        order.replaceLine(newLine, offer.maxPerOrder, actor.userId, now).failureOrNull()?.let { return Failure.of(it) }
        val held = inventoryDomainService.rebalanceForOrder(order.code, before, order.lines, actor.userId, now).unwrapOr { refused ->
            return Failure.Refused(refused.code, refused.meta)
        }
        held.forEach(inventoryRepository::save)
        return null
    }

    /**
     * What a change leaves over goes back: a PENDING refund (BANK_TRANSFER, staff call for the IBAN), never more than the
     * order's money still allows (paid, minus every refund not FAILED). [payments] are the order's, locked.
     */
    private fun refundOf(order: Order, payments: List<Payment>, amount: Money, actor: PanelActor, now: Instant): Refund? {
        val refundable = refundDomainService.refundable(refundDomainService.paidBy(payments), refundRepository.lockAllOf(order.id))
        if (amount > refundable) logger.warn("ResolveChangeRequestUc: order ${order.code.value} is to give back ${amount.toman}, but only ${refundable.toman} is refundable; that is refunded")
        val given = if (amount > refundable) refundable else amount
        if (given == Money.ZERO) return null
        return Refund.start(refundIdGenerator.nextRefundId(), order.id, given, RefundMethod.BANK_TRANSFER, RefundSource.CHANGE_REQUEST, null, actor.userId, now)
            .also(refundRepository::add)
    }

    private fun audit(code: OrderCode, decision: ResolutionDecision, type: ChangeType, from: VariantId?, to: VariantId?, actor: PanelActor, now: Instant) {
        auditTrail.record(
            AuditEntry(
                now, actor, AuditAction.ORDER_CHANGE_RESOLVED, AuditEntityType.ORDER, code.value, code.value,
                AuditDiff.build {
                    field("decision", null, decision)
                    field("changeType", null, type)
                    field("variantId", from?.value, to?.value)
                },
            ),
        )
    }

    /** The body's shape (400), every problem at once. */
    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val decision = ResolutionDecision.entries.firstOrNull { it.name == command.decision?.trim() }
            ?: null.also { problems += FieldFailure("decision", Problem.DecisionInvalid) }
        val note = when (val parsed = StatusNote.makeNew(command.note)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val variantId = command.variantId?.let { raw ->
            when {
                decision == ResolutionDecision.REJECT -> null.also { problems += FieldFailure("variantId", Problem.VariantNotAllowed) }
                else -> VariantId.makeNew(raw).getOrNull() ?: null.also { problems += FieldFailure("variantId", Problem.VariantInvalid) }
            }
        }
        if (decision == ResolutionDecision.REJECT && note == null && problems.none { it.field == "note" }) problems += FieldFailure("note", Problem.NoteRequired)
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        return Result.success(Input(checkNotNull(decision), variantId, note, command.notifyCustomer ?: true))
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object DecisionInvalid : Problem("RESOLUTION_DECISION_INVALID")

        data object NoteRequired : Problem("NOTE_REQUIRED")

        data object VariantNotAllowed : Problem("VARIANT_NOT_ALLOWED")

        data object VariantInvalid : Problem("DESIRED_VARIANT_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** No change request is open (422), with where `POST …/status` may take the order instead. */
        data class InvalidTransition(val from: OrderStatus) : Failure(
            "INVALID_STATUS_TRANSITION",
            FailureKind.BUSINESS_RULE,
            meta = mapOf("from" to from.name, "to" to OrderStatus.PROCESSING.name, "allowed" to OrderTransitionPolicy.allowedFrom(from).map { it.name }.sorted()),
        )

        /**
         * A rule refused it (422): PAYMENT_IN_PROGRESS, PRODUCT_UNAVAILABLE, QUANTITY_EXCEEDS_STOCK (with `availableQuantity`),
         * QUANTITY_EXCEEDS_LIMIT, or what changed meanwhile.
         */
        data class Refused(val reason: String, val details: Map<String, Any>) : Failure(reason, FailureKind.BUSINESS_RULE, meta = details)

        companion object {
            /** A refusal of the order's: on its field (400 NOTE_REQUIRED, DESIRED_VARIANT_INVALID) or 422. */
            fun of(failure: ResultFailure): Failure =
                if (failure.kind == FailureKind.INVALID) InvalidInput(listOf(failure)) else Refused(failure.code, failure.meta)
        }
    }
}
