package com.kiva.domain.aggregate.contact

import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import java.security.SecureRandom
import java.util.random.RandomGenerator

/** A new contact message's code: `CT-` and six random digits (roadmap phase 13, decision 4). Tests give their own [random]. */
class TicketCodeGenerator(private val random: RandomGenerator = SecureRandom()) {
    fun next(): TicketCode = TicketCode.ofNumber(random.nextInt(1_000_000))

    companion object {
        /** A code already taken is drawn again, at most this many times in all; then the request fails (500). */
        const val MAX_TRIES = 5
    }
}
