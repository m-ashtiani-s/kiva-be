package com.kiva.application.usecase.auth

import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.common.logging.getLogger
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.entity.OtpChallenge
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy.Refusal
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import java.time.Instant

/**
 * Sending codes, for every use case that sends them (login, phone change): the phones' locks, the limits, and making a
 * code. Runs inside the caller's transaction; the caller sends the SMS after its commit.
 *
 * @param maxSmsPerHour the shop's cap on SMS in an hour, all phones together (`kiva.auth.maxSmsPerHour`).
 */
class OtpIssuer(
    private val otpChallengeRepository: OtpChallengeRepository,
    private val otpCodes: OtpCodes,
    private val securityEventLog: SecurityEventLog,
    private val maxSmsPerHour: Int,
) {
    private val logger = getLogger()

    init {
        require(maxSmsPerHour > 0) { "maxSmsPerHour must be positive, was $maxSmsPerHour" }
    }

    /** A code about to be sent: to [phone], for [purpose]. */
    data class Recipient(val phone: IranianMobileNumber, val purpose: OtpPurpose)

    /**
     * Takes the lock of every phone in [recipients] (in the order of their numbers, so two requests about the same two
     * phones can't wait for each other), then says why no code may go to them now, or null if all may.
     *
     * Each phone's own limits, then the shop's hourly cap counting every code about to be sent. Reaching the cap is an
     * ERROR in the log and SMS_HOURLY_CAP_REACHED in the security log; the caller must commit for that event to stay.
     */
    fun lockAndCheck(recipients: List<Recipient>, now: Instant): Refusal? {
        recipients.map { it.phone }.distinct().sortedBy { it.value }.forEach(otpChallengeRepository::lockPhone)
        recipients.forEach { recipient ->
            val sent = otpChallengeRepository.sentTo(recipient.phone, now.minus(OtpPolicy.DAY))
            OtpPolicy.refusalToSend(recipient.purpose, sent, now)?.let { return it }
        }
        return shopCapRefusal(recipients.size, now)
    }

    /**
     * A new code for [recipient], replacing the open one of the same phone and purpose. Stored as a salted HMAC; the
     * code itself is returned for the SMS only.
     */
    fun issue(recipient: Recipient, userId: UserId?, authorizesPhone: IranianMobileNumber?, requestIp: String?, now: Instant): OtpCode {
        otpChallengeRepository.findOpen(recipient.phone, recipient.purpose)?.let { otpChallengeRepository.save(it.replace(now)) }
        val new = otpCodes.newCode(recipient.purpose, recipient.phone)
        val challenge = OtpChallenge.issue(
            otpChallengeRepository.nextId(), recipient.purpose, recipient.phone, userId, authorizesPhone, new.salt, new.hash, now,
        )
        otpChallengeRepository.add(challenge, requestIp)
        return new.code
    }

    /**
     * The shop's cap counts every phone's codes, but the locks are only these phones': two requests at the same moment
     * can both see the last free places. The cap is a safety valve for the SMS bill, so going over it by a few is fine.
     */
    private fun shopCapRefusal(codesToSend: Int, now: Instant): Refusal? {
        val sent = otpChallengeRepository.sentToAnyone(now.minus(OtpPolicy.HOUR))
        val refusal = OtpPolicy.refusalForShopCap(sent.count + codesToSend - 1, sent.earliest, maxSmsPerHour, now) ?: return null
        logger.error("OtpIssuer: the shop's cap of $maxSmsPerHour SMS an hour is reached; no code goes out for ${refusal.retryAfter}")
        securityEventLog.record(SecurityEvent(SecurityEventType.SMS_HOURLY_CAP_REACHED, now, detail = mapOf("cap" to maxSmsPerHour)))
        return refusal
    }
}
