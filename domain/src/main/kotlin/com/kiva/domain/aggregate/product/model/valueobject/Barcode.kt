package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The barcode printed on one color's label (contract `^[0-9A-Za-z-]{4,32}$`). Kept exactly as typed, case included:
 * the panel finds a product by it exactly. Blank means none.
 */
@JvmInline
value class Barcode private constructor(val value: String) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(FORMAT.matches(value)) { "Invalid Barcode '$value'" }
    }

    companion object {
        private val FORMAT = Regex("[0-9A-Za-z-]{4,32}")

        /** Null for a missing or blank barcode. */
        fun makeNew(raw: String?): Result<Barcode?, Failure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return if (FORMAT.matches(text)) Result.success(Barcode(text)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): Barcode = Barcode(value)
    }

    // No field here: the caller names it (`variants.0.barcode`, `barcode`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("BARCODE_INVALID")
    }
}
