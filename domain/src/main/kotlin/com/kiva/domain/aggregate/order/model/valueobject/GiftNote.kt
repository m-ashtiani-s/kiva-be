package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * «سفارش هدیه»: a message for the card, and whether the parcel hides the prices. The gift wrap itself isn't kept:
 * while the checkout offers no wrap (`giftWrap.available = false`) it is always no wrap, at no cost; it comes with its
 * own field when the shop offers it.
 *
 * @property message for the card; optional ([OptionalText]). Personal: [toString] hides it.
 * @property hidePrices no prices in the parcel (the contract's default).
 */
class GiftNote private constructor(val message: String?, val hidePrices: Boolean) {

    init {
        require(message == null || OptionalText.isValid(message, MESSAGE_MAX_LENGTH)) { "Invalid gift message of length ${message?.length}" }
    }

    override fun equals(other: Any?): Boolean = other is GiftNote && other.message == message && other.hidePrices == hidePrices

    override fun hashCode(): Int = 31 * message.hashCode() + hidePrices.hashCode()

    override fun toString(): String = "GiftNote(message=${if (message == null) "none" else "***"}, hidePrices=$hidePrices)"

    /** Without its message, erased with its customer's account (decision 2 of phase 14); whether prices are hidden stays. */
    fun erased(): GiftNote = GiftNote(null, hidePrices)

    companion object {
        /** From the contract's PlaceOrderRequest.gift. */
        const val MESSAGE_MAX_LENGTH = 200

        fun makeNew(message: String?, hidePrices: Boolean): Result<GiftNote, Failure> =
            when (val checked = OptionalText.check(message, MESSAGE_MAX_LENGTH)) {
                is OptionalText.Checked.Fine -> Result.success(GiftNote(checked.value, hidePrices))
                OptionalText.Checked.TooLong -> Result.failure(Failure.MessageTooLong)
                OptionalText.Checked.InvalidCharacters -> Result.failure(Failure.MessageInvalid)
            }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(message: String?, hidePrices: Boolean): GiftNote = GiftNote(message, hidePrices)
    }

    // The `message` of the contract's gift; the caller puts it under `gift.`.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = "message", meta = meta) {
        data object MessageTooLong : Failure("GIFT_MESSAGE_TOO_LONG", mapOf("maxLength" to MESSAGE_MAX_LENGTH))
        data object MessageInvalid : Failure("GIFT_MESSAGE_INVALID")
    }
}
