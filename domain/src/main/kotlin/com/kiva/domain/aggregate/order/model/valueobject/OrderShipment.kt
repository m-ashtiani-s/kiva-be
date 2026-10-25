package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * The carrier's tracking code of a parcel (`TPX4821760`, or Post's 24 digits): 6 to 40 Latin letters, digits and
 * dashes, kept in capitals so the same code typed twice is the same code. Persian digits are taken as Latin.
 */
@JvmInline
value class TrackingCode private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(FORMAT.matches(value)) { "Invalid TrackingCode '$value'" }
    }

    companion object {
        const val MIN_LENGTH = 6
        const val MAX_LENGTH = 40
        private val FORMAT = Regex("^[0-9A-Z-]{$MIN_LENGTH,$MAX_LENGTH}$")

        fun makeNew(raw: String?): Result<TrackingCode, Failure> {
            val text = raw?.trim()?.normalizeDigits()?.uppercase().orEmpty()
            return when {
                text.isEmpty() -> Result.failure(Failure.Required)
                FORMAT.matches(text) -> Result.success(TrackingCode(text))
                else -> Result.failure(Failure.Invalid)
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): TrackingCode = TrackingCode(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "trackingCode") {
        data object Required : Failure("TRACKING_CODE_REQUIRED")
        data object Invalid : Failure("TRACKING_CODE_INVALID")
    }
}

/**
 * The parcel an order left in: the carrier, its tracking code, when it was handed over, and whether it is listed (masked)
 * on «کدهای رهگیری روزانه».
 *
 * @property carrier always the order's own shipping method: the customer paid for that one.
 */
data class OrderShipment(val carrier: ShippingMethodCode, val trackingCode: TrackingCode, val shippedOn: Instant, val showInDailyList: Boolean)

/**
 * The message the operator wrote with the photos (`message` of `…/pre-shipment-media/send`): only kept, never delivered by
 * the server, since the operator sends the photos by hand. Optional, up to [MAX_LENGTH]; personal, never logged.
 */
@JvmInline
value class PhotoMessage private constructor(val value: String) {

    init {
        require(OptionalText.isValid(value, MAX_LENGTH)) { "Invalid PhotoMessage of length ${value.length}" }
    }

    override fun toString(): String = "PhotoMessage(***)"

    companion object {
        const val MAX_LENGTH = 500

        /** Missing or blank means no message (null), not an error. */
        fun makeNew(raw: String?): Result<PhotoMessage?, Failure> = when (val checked = OptionalText.check(raw, MAX_LENGTH)) {
            is OptionalText.Checked.Fine -> Result.success(checked.value?.let(::PhotoMessage))
            OptionalText.Checked.TooLong, OptionalText.Checked.InvalidCharacters -> Result.failure(Failure.TooLong)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): PhotoMessage = PhotoMessage(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "message", meta = mapOf("maxLength" to MAX_LENGTH)) {
        data object TooLong : Failure("PHOTO_MESSAGE_TOO_LONG")
    }
}
