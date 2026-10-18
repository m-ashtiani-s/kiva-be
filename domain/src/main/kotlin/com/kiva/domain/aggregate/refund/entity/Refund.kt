package com.kiva.domain.aggregate.refund.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.model.valueobject.BankReference
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundNote
import com.kiva.domain.aggregate.refund.model.valueobject.RefundReason
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.COMPLETED
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.FAILED
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.PENDING
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.PROCESSING
import java.time.Duration
import java.time.Instant

/**
 * Money to give back to a customer, by hand (roadmap section 1, row 3): the shop only records it. Staff transfer the money
 * in their bank's app and record the bank's reference here; no gateway or bank is ever called. Kept as a row: a piece of
 * work with a few steps, not a history (the order's history gets its own fact, OrderRefunded, when it is completed).
 *
 * ```
 * PENDING ─→ PROCESSING ─┬─→ COMPLETED (with the bank's reference)
 *    └───────────────────┴─→ FAILED
 * ```
 *
 * Rules it keeps by itself:
 * - it is for more than nothing, and starts PENDING, expected to be dealt with within [EXPECTED_WITHIN];
 * - it only moves as drawn above; COMPLETED and FAILED are final ([InvalidTransition]);
 * - it is completed only with the bank's reference, whichever the method.
 *
 * What it can't know: how much of the order is still refundable (RefundDomainService, with the order's payments and its
 * other refunds), and that only one change happens at once (the order's row is locked first, roadmap section 4.11 lock 9).
 *
 * @property statusNote the note of its last change, which the customer sees too.
 * @property version the row's, for the repository's check that nobody saved it in the meantime.
 */
class Refund private constructor(
    val id: RefundId,
    val orderId: OrderId,
    val amount: Money,
    val method: RefundMethod,
    status: RefundStatus,
    val source: RefundSource,
    val reason: RefundReason?,
    val returnId: Long?,
    val expectedBy: Instant,
    referenceId: BankReference?,
    statusNote: RefundNote?,
    val createdOn: Instant,
    val createdBy: UserId,
    processingOn: Instant?,
    completedOn: Instant?,
    failedOn: Instant?,
    val version: Long,
) {
    var status: RefundStatus = status
        private set
    var referenceId: BankReference? = referenceId
        private set
    var statusNote: RefundNote? = statusNote
        private set
    var processingOn: Instant? = processingOn
        private set
    var completedOn: Instant? = completedOn
        private set
    var failedOn: Instant? = failedOn
        private set

    init {
        require(amount > Money.ZERO) { "Refund ${id.value} of nothing" }
        require((status == COMPLETED) == (completedOn != null)) { "Refund ${id.value} is $status with completedOn=$completedOn" }
        require((status == FAILED) == (failedOn != null)) { "Refund ${id.value} is $status with failedOn=$failedOn" }
        require(status != COMPLETED || referenceId != null) { "Refund ${id.value} completed without the bank's reference" }
    }

    /** Staff are on it. Only from PENDING; asking again while PROCESSING changes nothing. */
    fun markProcessing(note: RefundNote?, now: Instant): Result<Refund, InvalidTransition> {
        if (status == PROCESSING) return Result.success(this)
        if (status != PENDING) return Result.failure(InvalidTransition(status, PROCESSING))
        status = PROCESSING
        processingOn = now
        statusNote = note ?: statusNote
        return Result.success(this)
    }

    /** The money went back: [referenceId] is the bank's reference of the transfer. From PENDING or PROCESSING, once. */
    fun complete(referenceId: BankReference?, note: RefundNote?, now: Instant): Result<Refund, CompleteFailure> {
        if (status.isFinal) return Result.failure(CompleteFailure.NotOpen(InvalidTransition(status, COMPLETED)))
        if (referenceId == null) return Result.failure(CompleteFailure.ReferenceRequired)
        status = COMPLETED
        this.referenceId = referenceId
        completedOn = now
        statusNote = note ?: statusNote
        return Result.success(this)
    }

    /** It couldn't be done (a wrong IBAN, the customer unreachable): staff open a new one when they can. From PENDING or PROCESSING. */
    fun fail(note: RefundNote?, now: Instant): Result<Refund, InvalidTransition> {
        if (status.isFinal) return Result.failure(InvalidTransition(status, FAILED))
        status = FAILED
        failedOn = now
        statusNote = note ?: statusNote
        return Result.success(this)
    }

    override fun equals(other: Any?): Boolean = other is Refund && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Refund(id=${id.value}, orderId=${orderId.value}, amount=$amount, status=$status)"

    companion object {
        /** How soon staff are expected to call the customer and send the money (`expectedBy`, an estimate). */
        val EXPECTED_WITHIN: Duration = Duration.ofHours(72)

        /** A new refund of [amount], PENDING from [now]. Whether that much is still refundable is RefundDomainService's to check first. */
        fun start(
            id: RefundId,
            orderId: OrderId,
            amount: Money,
            method: RefundMethod,
            source: RefundSource,
            reason: RefundReason?,
            by: UserId,
            now: Instant,
        ): Refund = Refund(
            id, orderId, amount, method, PENDING, source, reason, returnId = null, expectedBy = now.plus(EXPECTED_WITHIN), referenceId = null,
            statusNote = null, createdOn = now, createdBy = by, processingOn = null, completedOn = null, failedOn = null, version = 0,
        )

        fun constructFromPersisted(
            id: RefundId,
            orderId: OrderId,
            amount: Money,
            method: RefundMethod,
            status: RefundStatus,
            source: RefundSource,
            reason: RefundReason?,
            returnId: Long?,
            expectedBy: Instant,
            referenceId: BankReference?,
            statusNote: RefundNote?,
            createdOn: Instant,
            createdBy: UserId,
            processingOn: Instant?,
            completedOn: Instant?,
            failedOn: Instant?,
            version: Long,
        ): Refund = Refund(
            id, orderId, amount, method, status, source, reason, returnId, expectedBy, referenceId, statusNote, createdOn, createdBy, processingOn,
            completedOn, failedOn, version,
        )
    }

    /** The refund can't go from [from] to [to] (422 INVALID_STATUS_TRANSITION). */
    data class InvalidTransition(val from: RefundStatus, val to: RefundStatus) :
        ResultFailure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = mapOf("from" to from.name, "to" to to.name))

    sealed class CompleteFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        /** Completed or failed already. */
        data class NotOpen(val transition: InvalidTransition) : CompleteFailure(transition.code, transition.kind, meta = transition.meta)

        /** Completed without the bank's reference (400 on `referenceId`). */
        data object ReferenceRequired : CompleteFailure("REFERENCE_ID_REQUIRED", FailureKind.INVALID, "referenceId")
    }
}
