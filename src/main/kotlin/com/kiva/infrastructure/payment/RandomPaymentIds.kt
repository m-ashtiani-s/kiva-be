package com.kiva.infrastructure.payment

import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.repository.PaymentIdGenerator
import java.security.SecureRandom

/**
 * Public ids of payment attempts from [SecureRandom]: `pay_` and 12 capital letters and digits (about 62 bits), so
 * nothing can be counted from them; the database's unique key stands behind a repeat.
 */
class RandomPaymentIds(private val random: SecureRandom = SecureRandom()) : PaymentIdGenerator {

    override fun nextPaymentPublicId(): PaymentPublicId =
        PaymentPublicId.constructFromPersisted("pay_" + buildString(ID_LENGTH) { repeat(ID_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } })

    companion object {
        const val ID_LENGTH = 12
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
