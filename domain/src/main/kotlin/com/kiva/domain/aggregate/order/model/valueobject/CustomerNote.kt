package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * «توضیح سفارش»: what the shopper wants the shop to know about the order. Optional ([OptionalText]). It may say
 * anything, so it never goes into a log: [toString] hides it.
 */
@JvmInline
value class CustomerNote private constructor(val value: String) {

    init {
        require(OptionalText.isValid(value, MAX_LENGTH)) { "Invalid CustomerNote of length ${value.length}" }
    }

    override fun toString(): String = "CustomerNote(***)"

    companion object {
        /** From the contract's PlaceOrderRequest. */
        const val MAX_LENGTH = 300

        /** Missing or blank means no note (null), not an error. */
        fun makeNew(raw: String?): Result<CustomerNote?, Failure> = when (val checked = OptionalText.check(raw, MAX_LENGTH)) {
            is OptionalText.Checked.Fine -> Result.success(checked.value?.let(::CustomerNote))
            OptionalText.Checked.TooLong -> Result.failure(Failure.TooLong)
            OptionalText.Checked.InvalidCharacters -> Result.failure(Failure.InvalidCharacters)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): CustomerNote = CustomerNote(value)
    }

    // Always the `customerNote` field of the contract's PlaceOrderRequest.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "customerNote", meta = meta) {
        data object TooLong : Failure("CUSTOMER_NOTE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
        data object InvalidCharacters : Failure("CUSTOMER_NOTE_INVALID")
    }
}
