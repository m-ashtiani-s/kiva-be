package com.kiva.domain.aggregate.otp.model.valueobject

/**
 * The id of one sent code. Never in the API: it only lets the repository find the row again. Handed out from 1
 * (a database sequence) before the code is stored, so zero or negative is a bug.
 */
@JvmInline
value class OtpChallengeId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid OtpChallengeId $value" }
    }

    companion object {
        /** For values from the sequence or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): OtpChallengeId = OtpChallengeId(value)
    }
}
