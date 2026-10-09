package com.kiva.application.port

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.CodeHash
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt

/**
 * Makes one-time codes and their hashes. The domain only compares hashes; how they are made (a secure random source,
 * an HMAC with a key kept outside the database) lives behind this port.
 */
interface OtpCodes {

    /**
     * A new random code for [phone] and [purpose], with a fresh salt and the hash to store. The code itself is for the
     * SMS only: it is never stored or logged.
     */
    fun newCode(purpose: OtpPurpose, phone: IranianMobileNumber): NewCode

    /**
     * The hash a typed [code] has with this [salt], [purpose] and [phone]: what to compare with the stored one. A code
     * sent for another phone or purpose gives another hash, so it never matches.
     */
    fun hash(code: OtpCode, salt: Salt, purpose: OtpPurpose, phone: IranianMobileNumber): CodeHash

    /** A code to send, and what to store about it. */
    data class NewCode(val code: OtpCode, val salt: Salt, val hash: CodeHash)
}
