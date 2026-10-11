package com.kiva.domain.aggregate.otp.repository

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.entity.OtpChallenge
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.valueobject.OtpChallengeId
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import java.time.Instant

/** Where sent codes are kept: one row each, short-lived. Everything runs inside the use case's transaction. */
interface OtpChallengeRepository {

    /**
     * Holds this phone's lock until the transaction ends, so everything about one phone's codes runs one request at a
     * time. A phone that has no code yet has no row to lock, hence a lock of its own: without it, ten sends at the
     * same moment would all see "no code yet" and send ten SMS.
     */
    fun lockPhone(phone: IranianMobileNumber)

    /** A new id, from a sequence: never handed out twice. */
    fun nextId(): OtpChallengeId

    /** The open code (neither accepted nor replaced) of this phone and purpose; there is at most one. */
    fun findOpen(phone: IranianMobileNumber, purpose: OtpPurpose): OtpChallenge?

    /**
     * The open code, locked until the transaction ends: tries of one code run one after another, so fifty at the same
     * moment can't all slip in before the count goes up, and the right code makes one session only. A try that waited
     * for the lock finds the code as the other one left it, or no open code at all once it was accepted.
     */
    fun findOpenForUpdate(phone: IranianMobileNumber, purpose: OtpPurpose): OtpChallenge?

    /** This phone's codes, any purpose, sent after [since]: what the send limits count. */
    fun sentTo(phone: IranianMobileNumber, since: Instant): List<OtpPolicy.SentCode>

    /** Codes sent to anyone after [since]: for the shop's hourly cap. */
    fun sentToAnyone(since: Instant): SentToAnyone

    /** Stores a code just issued. [requestIp] is the address it was asked from. */
    fun add(challenge: OtpChallenge, requestIp: String?)

    /** Saves a change to a stored code: a try, its acceptance, or its replacement. */
    fun save(challenge: OtpChallenge)

    /** @property earliest the oldest of them; null when [count] is 0. */
    data class SentToAnyone(val count: Int, val earliest: Instant?)
}
