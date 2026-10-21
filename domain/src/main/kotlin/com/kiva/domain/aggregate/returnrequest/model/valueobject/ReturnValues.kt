package com.kiva.domain.aggregate.returnrequest.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * Where a return is (the contract's `ReturnStatus`; roadmap section 12.3):
 *
 * ```
 * REQUESTED ─┬─→ REJECTED
 *            └─→ APPROVED ─→ PICKUP_SCHEDULED (again and again) ─┬─→ RECEIVED ─┬─→ REFUNDED
 *                   └────────────────────────────────────────────┤             └─→ CLOSED
 *                                                                └─→ CLOSED
 * ```
 *
 * [REJECTED], [REFUNDED] and [CLOSED] are final.
 */
enum class ReturnStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    PICKUP_SCHEDULED,
    RECEIVED,
    REFUNDED,
    CLOSED,
    ;

    /** Done, one way or the other: nothing moves it any more. */
    val isFinal: Boolean get() = this == REJECTED || this == REFUNDED || this == CLOSED

    companion object {
        /** The `status` filter of the panel's queue, exactly as the contract names it; anything else is RETURN_STATUS_INVALID. */
        fun makeNew(raw: String): Result<ReturnStatus, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "status") {
        data object Invalid : Failure("RETURN_STATUS_INVALID")
    }
}

/**
 * Why the customer sends it back (the contract's `ReturnReason`). The first four are the shop's fault ([isKivaFault]):
 * the shop pays the way back by default and wants a photo or video of the problem (roadmap section 12.2, decisions 4 and
 * 5). Damage on the way is not the customer's fault: the shop claims it from the carrier.
 */
enum class ReturnReason(val isKivaFault: Boolean) {
    NOT_AS_PICTURED(true),
    MANUFACTURING_DEFECT(true),
    WRONG_ITEM(true),
    DAMAGED_IN_TRANSIT(true),
    CHANGED_MIND(false),
    OTHER(false),
    ;

    companion object {
        /** As the contract sends it, exactly; anything else is RETURN_REASON_INVALID. */
        fun makeNew(raw: String): Result<ReturnReason, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "reason") {
        data object Invalid : Failure("RETURN_REASON_INVALID")
    }
}

/** Who pays for the way back to the shop (`shippingPaidBy`). */
enum class ReturnShippingPayer {
    KIVA,
    CUSTOMER,
    ;

    companion object {
        /** As the contract sends it, exactly; anything else is SHIPPING_PAYER_INVALID. */
        fun makeNew(raw: String): Result<ReturnShippingPayer, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "shippingPaidBy") {
        data object Invalid : Failure("SHIPPING_PAYER_INVALID")
    }
}

/**
 * How a return ended, as its order records it (`OrderReturnEnded`): turned down ([REJECTED], the order may be returned
 * again within its window), closed without money back ([CLOSED]), or its money given back ([REFUNDED]). Stored by name in
 * the order's events, so never renamed.
 */
enum class ReturnOutcome {
    REJECTED,
    CLOSED,
    REFUNDED,
    ;

    /** After it the order is never returned again (`Order.returnSettled`). */
    val settlesReturn: Boolean get() = this != REJECTED
}
