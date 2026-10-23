package com.kiva.domain.aggregate.contact.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import java.time.Duration
import java.time.Instant

/**
 * The limits of the contact form (roadmap phase 13, decision 3): per address in memory (the RateLimit plugin, [MAX_PER_IP]
 * in [IP_WINDOW]), and per mobile here, counted from the table: [MAX_PER_PHONE] in [PHONE_WINDOW], whatever the address.
 */
object ContactPolicy {
    const val MAX_PER_IP = 5
    val IP_WINDOW: Duration = Duration.ofHours(1)

    const val MAX_PER_PHONE = 5
    val PHONE_WINDOW: Duration = Duration.ofHours(24)

    /**
     * Null when one more message from this mobile is fine; else how long until the oldest of [recent] (the times of its
     * messages in the last [PHONE_WINDOW]) is out of the window.
     */
    fun phoneLimit(recent: List<Instant>, now: Instant): RateLimited? {
        if (recent.size < MAX_PER_PHONE) return null
        val freesAt = recent.sorted()[recent.size - MAX_PER_PHONE].plus(PHONE_WINDOW)
        return RateLimited(Duration.between(now, freesAt))
    }

    /** Wait [retryAfter] (429 with Retry-After), at least a second. */
    data class RateLimited(val retryAfter: Duration) :
        ResultFailure("RATE_LIMITED", FailureKind.RATE_LIMITED, meta = mapOf("retryAfterSeconds" to ((retryAfter.toMillis() + 999) / 1000).coerceAtLeast(1)))
}
