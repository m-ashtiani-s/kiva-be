package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import java.time.Instant

/** Who cancelled an order: the customer themselves (`POST /me/orders/{code}/cancel`), or staff in the panel. */
enum class CancelledBy {
    CUSTOMER,
    STAFF,
}

/** Why the customer cancelled (the contract's CancelOrderRequest.reason). Staff give a note instead. */
enum class CancelReason {
    CHANGED_MIND,
    NOT_AS_PICTURED,
    ORDERED_BY_MISTAKE,
    FOUND_CHEAPER,
    DELIVERY_TOO_LONG,
    OTHER,
    ;

    companion object {
        /** The reason as the contract sends it, exactly; anything else is CANCEL_REASON_INVALID. */
        fun makeNew(raw: String): Result<CancelReason, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "reason") {
        data object Invalid : Failure("CANCEL_REASON_INVALID")
    }
}

/**
 * A short note that goes with a change of an order's status: why the customer cancelled («OTHER»), or what staff want
 * them to read on the order's timeline. Optional ([OptionalText]), up to [MAX_LENGTH]. It may say anything, so it never
 * goes into a log: [toString] hides it.
 */
@JvmInline
value class StatusNote private constructor(val value: String) {

    init {
        require(OptionalText.isValid(value, MAX_LENGTH)) { "Invalid StatusNote of length ${value.length}" }
    }

    override fun toString(): String = "StatusNote(***)"

    companion object {
        const val MAX_LENGTH = 500

        /** Missing or blank means no note (null), not an error. The caller names the field and its code. */
        fun makeNew(raw: String?): Result<StatusNote?, Failure> = when (val checked = OptionalText.check(raw, MAX_LENGTH)) {
            is OptionalText.Checked.Fine -> Result.success(checked.value?.let(::StatusNote))
            OptionalText.Checked.TooLong -> Result.failure(Failure.TooLong)
            OptionalText.Checked.InvalidCharacters -> Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): StatusNote = StatusNote(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field = "note", meta = meta) {
        data object TooLong : Failure("STATUS_NOTE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object Invalid : Failure("STATUS_NOTE_INVALID")
    }
}

/**
 * How an order was cancelled: by whom, the customer's reason (staff give none), the note, and when.
 *
 * @property note shown on the customer's timeline (staff are told so); personal, never logged.
 */
data class OrderCancellationInfo(val by: CancelledBy, val reason: CancelReason?, val note: StatusNote?, val cancelledOn: Instant) {
    init {
        require(by == CancelledBy.CUSTOMER || reason == null) { "Only a customer gives a reason to cancel" }
    }

    override fun toString(): String = "OrderCancellationInfo(by=$by, reason=$reason, cancelledOn=$cancelledOn)"
}
