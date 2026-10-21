package com.kiva.domain.aggregate.returnrequest.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnTransitionPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReceiptLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReceipt
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.APPROVED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.CLOSED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.PICKUP_SCHEDULED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.RECEIVED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REFUNDED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REJECTED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REQUESTED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import java.time.Instant

/**
 * A customer sends lines of a delivered order back (roadmap section 12): a piece of work with a few steps, kept as a row,
 * not a history (decision 11 of section 1). Its money and its stock have their own histories already: the order records
 * the return's steps that change it (OrderReturnRequested, OrderReturnReceived, OrderReturnEnded), the stock what came
 * back on the shelf, the refund the money.
 *
 * ```
 * REQUESTED ─┬─→ REJECTED
 *            └─→ APPROVED ─→ PICKUP_SCHEDULED (again: replaced) ─┬─→ RECEIVED ─┬─→ REFUNDED (its refund completed)
 *                   └────────────────────────────────────────────┤             └─→ CLOSED
 *                                                                └─→ CLOSED
 * ```
 *
 * Rules it keeps by itself:
 * - it moves only as drawn (ReturnTransitionPolicy); any other step is [InvalidTransition] (422);
 * - it pays the way back as its reason suggests (ReturnPolicy.defaultPayerOf) until staff approve it otherwise;
 * - it is turned down and closed only with a reason;
 * - its receipt has each of its lines exactly once, never more of a line than went back, never more on the shelf than came;
 * - it is closed only while no refund of it is open, and REFUNDED only from RECEIVED, by one refund.
 *
 * What it can't know: its order (whether it may be returned, its lines: Order.requestReturn and ReturnDomainService), its
 * refunds (the use case says whether one is open), whether its media are the customer's (ReturnDomainService), and that
 * only one change happens at once (the order's row is locked first, then the return's: roadmap section 4.11, locks 9 and 10).
 *
 * @property version the row's, for the repository's check that nobody saved it in the meantime.
 */
class ReturnRequest private constructor(
    val id: ReturnId,
    val orderId: OrderId,
    val orderCode: OrderCode,
    val userId: UserId,
    status: ReturnStatus,
    val reason: ReturnReason,
    val description: ReturnText?,
    val items: List<ReturnItem>,
    val mediaIds: List<MediaId>,
    val refundMethod: RefundMethod,
    shippingPaidBy: ReturnShippingPayer,
    instructions: ReturnText?,
    rejectionReason: ReturnText?,
    pickup: ReturnPickup?,
    receipt: ReturnReceipt?,
    closeReason: ReturnText?,
    refundId: RefundId?,
    val createdOn: Instant,
    decidedOn: Instant?,
    decidedBy: UserId?,
    receivedOn: Instant?,
    receivedBy: UserId?,
    closedOn: Instant?,
    refundedOn: Instant?,
    val version: Long,
) {
    val code: ReturnCode = ReturnCode.forId(id)

    var status: ReturnStatus = status
        private set
    var shippingPaidBy: ReturnShippingPayer = shippingPaidBy
        private set

    /** Staff's own instructions to send it back; none means the shop's default text for [shippingPaidBy] (ReturnTexts). */
    var instructions: ReturnText? = instructions
        private set
    var rejectionReason: ReturnText? = rejectionReason
        private set
    var pickup: ReturnPickup? = pickup
        private set
    var receipt: ReturnReceipt? = receipt
        private set
    var closeReason: ReturnText? = closeReason
        private set

    /** The refund that gave its money back; there once REFUNDED. */
    var refundId: RefundId? = refundId
        private set
    var decidedOn: Instant? = decidedOn
        private set
    var decidedBy: UserId? = decidedBy
        private set
    var receivedOn: Instant? = receivedOn
        private set
    var receivedBy: UserId? = receivedBy
        private set
    var closedOn: Instant? = closedOn
        private set
    var refundedOn: Instant? = refundedOn
        private set

    init {
        require(items.isNotEmpty() && items.size <= ReturnPolicy.MAX_ITEMS && items.distinctBy { it.lineId }.size == items.size) { "Return ${id.value}: items ${items.size}" }
        require(mediaIds.size <= ReturnPolicy.MAX_MEDIA && mediaIds.distinct().size == mediaIds.size) { "Return ${id.value}: media ${mediaIds.size}" }
        require((status == REJECTED) == (rejectionReason != null)) { "Return ${id.value} is $status with a rejection reason ${rejectionReason != null}" }
        require((status == CLOSED) == (closedOn != null && closeReason != null)) { "Return ${id.value} is $status with closedOn=$closedOn" }
        require((status == REFUNDED) == (refundedOn != null && refundId != null)) { "Return ${id.value} is $status with refundedOn=$refundedOn" }
        require(status != PICKUP_SCHEDULED || pickup != null) { "Return ${id.value} is PICKUP_SCHEDULED without its pickup" }
        require((status != RECEIVED && status != REFUNDED) || (receipt != null && receivedOn != null)) { "Return ${id.value} is $status without its receipt" }
        require(status == REQUESTED || decidedOn != null) { "Return ${id.value} is $status without a decision" }
    }

    /**
     * Staff approve it: APPROVED, the customer sends it back (or the shop collects it). [payer] null keeps what its reason
     * suggested; [instructions] null shows the default text for the payer. Only from REQUESTED.
     */
    fun approve(payer: ReturnShippingPayer?, instructions: ReturnText?, by: UserId, now: Instant): Result<ReturnRequest, InvalidTransition> {
        refuseUnless(APPROVED)?.let { return Result.failure(it) }
        status = APPROVED
        shippingPaidBy = payer ?: shippingPaidBy
        this.instructions = instructions
        decided(by, now)
        return Result.success(this)
    }

    /** Staff turn it down, saying why (the customer reads it): REJECTED, final. Only from REQUESTED. */
    fun reject(reason: ReturnText, by: UserId, now: Instant): Result<ReturnRequest, InvalidTransition> {
        refuseUnless(REJECTED)?.let { return Result.failure(it) }
        status = REJECTED
        rejectionReason = reason
        decided(by, now)
        return Result.success(this)
    }

    /** The shop collects the parcel: PICKUP_SCHEDULED with [pickup]; again replaces it as a whole. From APPROVED or PICKUP_SCHEDULED. */
    fun schedulePickup(pickup: ReturnPickup): Result<ReturnRequest, InvalidTransition> {
        refuseUnless(PICKUP_SCHEDULED)?.let { return Result.failure(it) }
        status = PICKUP_SCHEDULED
        this.pickup = pickup
        return Result.success(this)
    }

    /**
     * The parcel reached the warehouse and was checked: RECEIVED with [lines]. From APPROVED or PICKUP_SCHEDULED. [lines]
     * have each line of the return exactly once (RECEIPT_ITEMS_MISMATCH with `meta.expectedItemIds`), never more of a line
     * than went back (RECEIVED_QUANTITY_EXCEEDS) nor more on the shelf than came (RESTOCK_EXCEEDS_RECEIVED). All of them 0
     * is fine: an empty parcel came, to be closed next.
     */
    fun receive(lines: List<ReceiptLine>, note: ReturnText?, by: UserId, now: Instant): Result<ReturnRequest, ReceiveFailure> {
        refuseUnless(RECEIVED)?.let { return Result.failure(ReceiveFailure.InvalidTransition(it)) }
        val expected = items.map { it.lineId }
        if (lines.map { it.lineId }.sorted() != expected.sorted()) return Result.failure(ReceiveFailure.ItemsMismatch(expected))
        val asked = items.associate { it.lineId to it.quantity }
        val problems = lines.flatMapIndexed { i, line ->
            listOfNotNull(
                FieldFailure("items.$i.receivedQuantity", ReceiveFailure.ReceivedExceeds(asked.getValue(line.lineId))).takeIf { line.received > asked.getValue(line.lineId) },
                FieldFailure("items.$i.restockQuantity", ReceiveFailure.RestockExceedsReceived).takeIf { line.restock > line.received },
            )
        }
        if (problems.isNotEmpty()) return Result.failure(ReceiveFailure.ItemsInvalid(problems))

        status = RECEIVED
        receipt = ReturnReceipt.constructFromPersisted(lines, note)
        receivedOn = now
        receivedBy = by
        return Result.success(this)
    }

    /**
     * Closed without money back, saying why ([reason]; the parcel never came, or the check found it wasn't the shop's
     * fault): CLOSED, final. From APPROVED, PICKUP_SCHEDULED or RECEIVED (REQUESTED is turned down instead), and only while
     * no refund of it is open ([hasOpenRefund]: RETURN_REFUND_OPEN): money on its way would belong to nobody. What went
     * back on the shelf stays there.
     */
    fun close(reason: ReturnText, hasOpenRefund: Boolean, now: Instant): Result<ReturnRequest, CloseFailure> {
        if (hasOpenRefund) return Result.failure(CloseFailure.RefundOpen)
        refuseUnless(CLOSED)?.let { return Result.failure(CloseFailure.InvalidTransition(it)) }
        status = CLOSED
        closeReason = reason
        closedOn = now
        return Result.success(this)
    }

    /** Its refund [refundId] was completed: REFUNDED, final. Only from RECEIVED; the same refund again changes nothing. */
    fun markRefunded(refundId: RefundId, now: Instant): Result<ReturnRequest, InvalidTransition> {
        if (status == REFUNDED && this.refundId == refundId) return Result.success(this)
        if (status != RECEIVED) return Result.failure(InvalidTransition(status, REFUNDED, allowedTransitions(hasOpenRefund = false)))
        status = REFUNDED
        this.refundId = refundId
        refundedOn = now
        return Result.success(this)
    }

    /** Where it may go now (the panel's `allowedTransitions`); [hasOpenRefund] is whether a refund of it is PENDING or PROCESSING. */
    fun allowedTransitions(hasOpenRefund: Boolean): Set<ReturnStatus> = ReturnTransitionPolicy.allowedFrom(status, hasOpenRefund)

    /**
     * Null when it may go to [to] now, the failure otherwise. Its refunds are not the return's to see: a step refused only
     * because a refund is open is the use case's to answer first (close's RETURN_REFUND_OPEN), so this asks as if none were.
     */
    private fun refuseUnless(to: ReturnStatus): InvalidTransition? {
        val allowed = allowedTransitions(hasOpenRefund = false)
        return if (to in allowed) null else InvalidTransition(status, to, allowed)
    }

    private fun decided(by: UserId, now: Instant) {
        decidedOn = now
        decidedBy = by
    }

    override fun equals(other: Any?): Boolean = other is ReturnRequest && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ReturnRequest(id=${id.value}, orderId=${orderId.value}, status=$status)"

    companion object {
        /**
         * A new return the customer asks for, REQUESTED from [now], its way back paid as [reason] suggests. Whether the
         * order may be returned, with these lines and this evidence, is ReturnDomainService's to check first.
         */
        fun request(
            id: ReturnId,
            orderId: OrderId,
            orderCode: OrderCode,
            userId: UserId,
            reason: ReturnReason,
            description: ReturnText?,
            items: List<ReturnItem>,
            mediaIds: List<MediaId>,
            refundMethod: RefundMethod,
            now: Instant,
        ): ReturnRequest = ReturnRequest(
            id, orderId, orderCode, userId, REQUESTED, reason, description, items, mediaIds, refundMethod, ReturnPolicy.defaultPayerOf(reason),
            instructions = null, rejectionReason = null, pickup = null, receipt = null, closeReason = null, refundId = null, createdOn = now,
            decidedOn = null, decidedBy = null, receivedOn = null, receivedBy = null, closedOn = null, refundedOn = null, version = 0,
        )

        fun constructFromPersisted(
            id: ReturnId,
            orderId: OrderId,
            orderCode: OrderCode,
            userId: UserId,
            status: ReturnStatus,
            reason: ReturnReason,
            description: ReturnText?,
            items: List<ReturnItem>,
            mediaIds: List<MediaId>,
            refundMethod: RefundMethod,
            shippingPaidBy: ReturnShippingPayer,
            instructions: ReturnText?,
            rejectionReason: ReturnText?,
            pickup: ReturnPickup?,
            receipt: ReturnReceipt?,
            closeReason: ReturnText?,
            refundId: RefundId?,
            createdOn: Instant,
            decidedOn: Instant?,
            decidedBy: UserId?,
            receivedOn: Instant?,
            receivedBy: UserId?,
            closedOn: Instant?,
            refundedOn: Instant?,
            version: Long,
        ): ReturnRequest = ReturnRequest(
            id, orderId, orderCode, userId, status, reason, description, items, mediaIds, refundMethod, shippingPaidBy, instructions, rejectionReason,
            pickup, receipt, closeReason, refundId, createdOn, decidedOn, decidedBy, receivedOn, receivedBy, closedOn, refundedOn, version,
        )
    }

    // ============ failures ============

    /** The return can't go from [from] to [to] (422 INVALID_STATUS_TRANSITION); [allowed] is where it may go instead. */
    data class InvalidTransition(val from: ReturnStatus, val to: ReturnStatus, val allowed: Set<ReturnStatus>) : ResultFailure(
        "INVALID_STATUS_TRANSITION",
        FailureKind.BUSINESS_RULE,
        meta = mapOf("from" to from.name, "to" to to.name, "allowed" to allowed.map { it.name }.sorted()),
    )

    sealed class ReceiveFailure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {
        data class InvalidTransition(val transition: ReturnRequest.InvalidTransition) :
            ReceiveFailure(transition.code, transition.kind, meta = transition.meta)

        /** Not each line of the return exactly once (400 on `items`); [expectedItemIds] are its lines. */
        data class ItemsMismatch(val expectedItemIds: List<Int>) :
            ReceiveFailure("RECEIPT_ITEMS_MISMATCH", FailureKind.INVALID, "items", meta = mapOf("expectedItemIds" to expectedItemIds.sorted()))

        /** Lines with more back than went, or more on the shelf than came, each under its field (400). */
        data class ItemsInvalid(val problems: List<ResultFailure>) : ReceiveFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** More of a line came than the return has of it; the field is added where it is found. */
        data class ReceivedExceeds(val maxQuantity: Int) : ReceiveFailure("RECEIVED_QUANTITY_EXCEEDS", FailureKind.INVALID, meta = mapOf("maxQuantity" to maxQuantity))

        /** More on the shelf than came; the field is added where it is found. */
        data object RestockExceedsReceived : ReceiveFailure("RESTOCK_EXCEEDS_RECEIVED", FailureKind.INVALID)
    }

    sealed class CloseFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data class InvalidTransition(val transition: ReturnRequest.InvalidTransition) : CloseFailure(transition.code, transition.kind, transition.meta)

        /** A refund of it is PENDING or PROCESSING: finish it or fail it first (422). */
        data object RefundOpen : CloseFailure("RETURN_REFUND_OPEN", FailureKind.BUSINESS_RULE)
    }
}
