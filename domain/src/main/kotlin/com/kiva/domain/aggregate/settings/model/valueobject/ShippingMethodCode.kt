package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** How an order travels. Values from the contract's ShippingMethodCode. */
enum class ShippingMethodCode {
    /** تیپاکس */
    TIPAX,

    /** پست معمولی */
    POST,

    /** پیک درون‌شهری; not offered yet (no row in the shop's settings). */
    COURIER,
    ;

    companion object {
        /** The value as the contract sends it (`POST`), exactly; anything else is SHIPPING_METHOD_INVALID. */
        fun makeNew(raw: String): Result<ShippingMethodCode, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field here: the caller names it (`method` on the cart, `shippingMethod` at checkout).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("SHIPPING_METHOD_INVALID")
    }
}
