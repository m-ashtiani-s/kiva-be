package com.kiva.application.dto

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import java.time.Instant

/**
 * Where the shopper goes to pay an attempt (the contract's PaymentInit).
 *
 * @property expiresAt the order's time to pay (`holdUntil`): the same for every attempt of the order, never moved.
 */
data class PaymentInitDto(
    val paymentId: String,
    val gateway: PaymentGatewayCode,
    val amount: Money,
    val redirect: PaymentRedirect,
    val expiresAt: Instant,
)
