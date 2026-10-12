package com.kiva.application.dto

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId

/**
 * What placing an order made, by id: the order, and its attempt to pay (null when there was nothing to pay, so the
 * order is paid already). The route opens the attempt's session with StartPaymentUc and builds its answer with queries.
 *
 * Also what an `Idempotency-Key` keeps of the request ([outcome]): a repeat answers from these very ids.
 */
data class PlacedOrder(val orderCode: OrderCode, val paymentId: PaymentPublicId?) {

    fun outcome(): Map<String, String> = buildMap {
        put(ORDER_CODE, orderCode.value)
        paymentId?.let { put(PAYMENT_ID, it.value) }
    }

    companion object {
        private const val ORDER_CODE = "orderCode"
        private const val PAYMENT_ID = "paymentId"

        /** The ids a key kept, written by [outcome]. */
        fun fromOutcome(outcome: Map<String, String>): PlacedOrder = PlacedOrder(
            OrderCode.constructFromPersisted(outcome[ORDER_CODE] ?: error("A placed order's key kept no order code: ${outcome.keys}")),
            outcome[PAYMENT_ID]?.let(PaymentPublicId::constructFromPersisted),
        )
    }
}
