package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** A payment gateway. Values from the contract's PaymentGatewayCode. */
enum class PaymentGatewayCode {
    /** زرین‌پال */
    ZARINPAL,

    /** بانک سامان */
    SAMAN,

    /** بانک ملت */
    MELLAT,
    ;

    companion object {
        /** The value as the contract sends it (`ZARINPAL`), exactly; anything else is PAYMENT_GATEWAY_INVALID. */
        fun makeNew(raw: String): Result<PaymentGatewayCode, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field here: the caller names it (`paymentGateway` at checkout, `gateway` on a retry).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("PAYMENT_GATEWAY_INVALID")
    }
}
