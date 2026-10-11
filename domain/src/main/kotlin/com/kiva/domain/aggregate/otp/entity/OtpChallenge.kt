package com.kiva.domain.aggregate.otp.entity

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.valueobject.CodeHash
import com.kiva.domain.aggregate.otp.model.valueobject.OtpChallengeId
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import java.time.Instant

/**
 * One code that was sent: to which phone, for what, its hash, and how many wrong tries it has taken.
 *
 * Stored in a plain table, short-lived. The code itself is never here, only its salted hash; this class does no
 * cryptography and only compares the hash it is given with its own.
 *
 * Its life ([statusAt]): ACTIVE until it is accepted (CONSUMED), takes its last wrong try (EXHAUSTED), is replaced by a
 * newer code for the same phone and purpose (REPLACED), or its five minutes run out (EXPIRED).
 *
 * Immutable: every change returns a new OtpChallenge, which the use case saves. A wrong try is a change too (one more
 * failed attempt), and the use case must commit it even though the answer to the user is a failure: otherwise the
 * count would be rolled back and the code could be guessed without limit.
 *
 * "Only one open code per phone and purpose" is not its job: it can't see the other codes. The use case replaces the
 * open one when sending a new one, and a unique index has the last word.
 *
 * @property userId who asked; null only for LOGIN, where nobody is known yet.
 * @property authorizesPhone for PHONE_CHANGE_CURRENT only: the new phone this code lets the account move to, so a code
 *   from the current phone can't be used to move it to any other number.
 */
class OtpChallenge private constructor(
    val id: OtpChallengeId,
    val purpose: OtpPurpose,
    val phone: IranianMobileNumber,
    val userId: UserId?,
    val authorizesPhone: IranianMobileNumber?,
    val salt: Salt,
    val codeHash: CodeHash,
    val failedAttempts: Int,
    val createdOn: Instant,
    val expiresOn: Instant,
    val consumedOn: Instant?,
    val replacedOn: Instant?,
) {
    init {
        require((purpose == OtpPurpose.LOGIN) == (userId == null)) { "Only a LOGIN code has no user: $purpose, user $userId" }
        require((purpose == OtpPurpose.PHONE_CHANGE_CURRENT) == (authorizesPhone != null)) {
            "Only a PHONE_CHANGE_CURRENT code authorizes a new phone: $purpose"
        }
        require(failedAttempts in 0..OtpPolicy.MAX_ATTEMPTS) { "failedAttempts out of range: $failedAttempts" }
        require(consumedOn == null || replacedOn == null) { "A code can't be both consumed and replaced" }
    }

    /** Neither accepted nor replaced yet. An exhausted or expired code is still open: the next code sent replaces it. */
    val isOpen: Boolean get() = consumedOn == null && replacedOn == null

    fun statusAt(now: Instant): Status = when {
        consumedOn != null -> Status.CONSUMED
        replacedOn != null -> Status.REPLACED
        failedAttempts >= OtpPolicy.MAX_ATTEMPTS -> Status.EXHAUSTED
        !now.isBefore(expiresOn) -> Status.EXPIRED
        else -> Status.ACTIVE
    }

    /**
     * Someone typed a code; [candidate] is its hash, made with this challenge's salt, purpose and phone.
     *
     * Only an ACTIVE code is compared. Once exhausted, even the right code is [Outcome.Exhausted]; after its five
     * minutes, even the right code is [Outcome.Expired]; neither of those counts as a try. An accepted or replaced code
     * is [Outcome.Expired] too: there is no active code to try.
     *
     * The returned challenge is what to save: one more failed attempt after a wrong code, consumed after the right one,
     * unchanged otherwise.
     */
    fun attempt(candidate: CodeHash, now: Instant): Attempt =
        when (statusAt(now)) {
            Status.CONSUMED, Status.REPLACED, Status.EXPIRED -> Attempt(this, Outcome.Expired)
            Status.EXHAUSTED -> Attempt(this, Outcome.Exhausted)
            Status.ACTIVE -> if (codeHash.matches(candidate)) {
                Attempt(copy(consumedOn = now), Outcome.Accepted)
            } else {
                val failed = failedAttempts + 1
                val outcome = if (failed >= OtpPolicy.MAX_ATTEMPTS) Outcome.Exhausted else Outcome.Wrong(OtpPolicy.MAX_ATTEMPTS - failed)
                Attempt(copy(failedAttempts = failed), outcome)
            }
        }

    /**
     * A newer code was sent for the same phone and purpose, so this one can't be used any more. Only an open code is
     * replaced; replacing an accepted or replaced one is a bug.
     */
    fun replace(now: Instant): OtpChallenge {
        check(isOpen) { "Code ${id.value} is not open, so there is nothing to replace" }
        return copy(replacedOn = now)
    }

    private fun copy(failedAttempts: Int = this.failedAttempts, consumedOn: Instant? = this.consumedOn, replacedOn: Instant? = this.replacedOn) =
        OtpChallenge(id, purpose, phone, userId, authorizesPhone, salt, codeHash, failedAttempts, createdOn, expiresOn, consumedOn, replacedOn)

    override fun equals(other: Any?): Boolean = other is OtpChallenge && other.id == id

    override fun hashCode(): Int = id.hashCode()

    // No phone, salt or hash: only what tells codes apart in a log.
    override fun toString(): String = "OtpChallenge(id=${id.value}, purpose=$purpose, failedAttempts=$failedAttempts)"

    enum class Status { ACTIVE, CONSUMED, EXHAUSTED, REPLACED, EXPIRED }

    /** What a try ended in, and the challenge to save. */
    data class Attempt(val challenge: OtpChallenge, val outcome: Outcome)

    sealed interface Outcome {
        /** The right code, in time: it is consumed now and can't be used again. */
        data object Accepted : Outcome

        /** A wrong code. [attemptsLeft] is at least 1: the last wrong try is [Exhausted]. */
        data class Wrong(val attemptsLeft: Int) : Outcome

        /** No tries left: a new code is needed. Also the answer to the try that used up the last one. */
        data object Exhausted : Outcome

        /** No active code to try: its time ran out, or it was accepted or replaced already. */
        data object Expired : Outcome
    }

    companion object {
        /**
         * A code just sent: active for [OtpPolicy.LIFETIME] from [now], no tries taken. [codeHash] is the hash of the
         * code with [salt], [purpose] and [phone], made by the code port.
         *
         * [id] is handed out before (a database sequence). [userId] goes with every purpose but LOGIN and
         * [authorizesPhone] with PHONE_CHANGE_CURRENT only; anything else is a bug in the caller.
         */
        fun issue(
            id: OtpChallengeId,
            purpose: OtpPurpose,
            phone: IranianMobileNumber,
            userId: UserId?,
            authorizesPhone: IranianMobileNumber?,
            salt: Salt,
            codeHash: CodeHash,
            now: Instant,
        ): OtpChallenge = OtpChallenge(
            id, purpose, phone, userId, authorizesPhone, salt, codeHash,
            failedAttempts = 0,
            createdOn = now,
            expiresOn = now.plus(OtpPolicy.LIFETIME),
            consumedOn = null,
            replacedOn = null,
        )

        fun constructFromPersisted(
            id: OtpChallengeId,
            purpose: OtpPurpose,
            phone: IranianMobileNumber,
            userId: UserId?,
            authorizesPhone: IranianMobileNumber?,
            salt: Salt,
            codeHash: CodeHash,
            failedAttempts: Int,
            createdOn: Instant,
            expiresOn: Instant,
            consumedOn: Instant?,
            replacedOn: Instant?,
        ): OtpChallenge = OtpChallenge(
            id, purpose, phone, userId, authorizesPhone, salt, codeHash, failedAttempts, createdOn, expiresOn, consumedOn, replacedOn,
        )
    }
}
