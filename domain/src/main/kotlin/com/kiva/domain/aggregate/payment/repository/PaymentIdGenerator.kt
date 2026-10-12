package com.kiva.domain.aggregate.payment.repository

import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId

/**
 * Hands out the public ids of new payment attempts (`pay_` and 12 random capital letters and digits): random, so they
 * tell nobody how many payments there are, and a database unique key stands behind a repeat. The real one uses
 * SecureRandom.
 */
interface PaymentIdGenerator {
    fun nextPaymentPublicId(): PaymentPublicId
}
