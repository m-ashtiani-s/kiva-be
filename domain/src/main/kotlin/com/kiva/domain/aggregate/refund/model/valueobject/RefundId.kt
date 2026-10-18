package com.kiva.domain.aggregate.refund.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** A refund's id (`Refund.id`, the path's `refundId`): a positive number from `refundid_seq`. */
@JvmInline
value class RefundId private constructor(val value: Long) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(value > 0) { "Invalid RefundId $value" }
    }

    companion object {
        /** From the path, as it came: anything but a positive whole number is no refund (the use case's 404). */
        fun makeNew(raw: String?): Result<RefundId, Failure> {
            val value = raw?.trim()?.toLongOrNull()
            return if (value != null && value > 0) Result.success(RefundId(value)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage, and the ones the generator makes. Still checked by `init`. */
        fun constructFromPersisted(value: Long): RefundId = RefundId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.NOT_FOUND) {
        data object Invalid : Failure("REFUND_NOT_FOUND")
    }
}
