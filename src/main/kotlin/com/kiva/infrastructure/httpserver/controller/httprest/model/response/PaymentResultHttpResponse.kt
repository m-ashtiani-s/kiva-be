package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.PaymentResultDto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentOutcome
import kotlinx.serialization.Serializable

/**
 * Contract schema `PaymentResult`: the result page. [failureReason] only for an attempt that didn't succeed, and
 * [nextSteps] only for one that did (empty otherwise). [order]'s `consolidatedInto` is `null` until round 2.
 */
@Serializable
data class PaymentResultHttpResponse(
    val paymentId: String,
    val status: String,
    val amount: Long,
    val gateway: String,
    val referenceId: String?,
    val cardMask: String?,
    val paidAt: String?,
    val failureReason: String?,
    val canRetry: Boolean,
    val order: PaymentResultOrderHttpResponse,
    val nextSteps: List<String>,
) {
    companion object {
        fun from(result: PaymentResultDto): PaymentResultHttpResponse {
            val order = result.order
            return PaymentResultHttpResponse(
                paymentId = result.paymentId,
                status = result.outcome.name,
                amount = result.amount.toman,
                gateway = result.gateway.name,
                referenceId = result.refId,
                cardMask = result.cardMask,
                paidAt = result.paidOn?.toString(),
                failureReason = result.failureReason?.let { PaymentTexts.failureReason(it, order.status) },
                canRetry = result.canRetry,
                order = PaymentResultOrderHttpResponse(order.code, order.status.name, reserved = order.status == OrderStatus.RESERVED, consolidatedInto = null),
                nextSteps = if (result.outcome == PaymentOutcome.SUCCEEDED) {
                    PaymentTexts.nextSteps(order.status, OrderTexts.messengerName(order.messengerChannel), order.shippingMethodName)
                } else {
                    emptyList()
                },
            )
        }
    }
}

/** The attempt's order as it stands now. */
@Serializable
data class PaymentResultOrderHttpResponse(val code: String, val status: String, val reserved: Boolean, val consolidatedInto: String?)
