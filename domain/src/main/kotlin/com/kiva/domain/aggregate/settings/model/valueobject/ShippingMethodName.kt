package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.text.normalizePersianLetters

/**
 * What the shop calls a shipping method («پست معمولی»): trimmed, its Persian letters made one way, 1 to 50 of them
 * (shippingmethod_tbl.name). Shown as it is, on the cart, the checkout and every order of that method.
 */
@JvmInline
value class ShippingMethodName private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value == value.trim()) { "Invalid ShippingMethodName '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 50

        /** Blank is FIELD_REQUIRED, longer than 50 TEXT_TOO_LONG; the caller names the field. */
        fun makeNew(raw: String): Result<ShippingMethodName, ResultFailure> {
            val text = raw.trim().normalizePersianLetters()
            return when {
                text.isEmpty() -> Result.failure(FieldRequired)
                text.length > MAX_LENGTH -> Result.failure(TextTooLong(MAX_LENGTH))
                else -> Result.success(ShippingMethodName(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): ShippingMethodName = ShippingMethodName(value)
    }
}
