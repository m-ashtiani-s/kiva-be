package com.kiva.infrastructure.auth

import com.kiva.application.port.OtpCodes
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.CodeHash
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Codes from [SecureRandom], hashed with HMAC-SHA256 under [pepper], a key kept outside the database.
 *
 * Why a keyed hash: a 5-digit code has only 100,000 values, so a plain hash, even salted, is reversed in a moment by
 * trying them all. Without the pepper, a copy of the database gives nothing to try them against.
 *
 * The HMAC covers `salt, purpose, phone, code`, each field ended by a zero byte so no two different inputs run
 * together into the same bytes. So a code matches only the phone and purpose it was sent for.
 *
 * @param pepper at least 32 random bytes (the `kiva.auth.otpPepper` setting). Changing it makes every open code
 *   unusable, which lasts at most the five minutes of a code.
 */
class HmacOtpCodes(pepper: ByteArray, private val random: SecureRandom = SecureRandom()) : OtpCodes {

    init {
        require(pepper.size >= MIN_PEPPER_BYTES) { "The OTP pepper must be at least $MIN_PEPPER_BYTES bytes, was ${pepper.size}" }
    }

    private val key = SecretKeySpec(pepper.copyOf(), ALGORITHM)

    override fun newCode(purpose: OtpPurpose, phone: IranianMobileNumber): OtpCodes.NewCode {
        val code = OtpCode.of(random.nextInt(OtpCode.UPPER_BOUND))
        val salt = Salt(ByteArray(Salt.LENGTH).also(random::nextBytes))
        return OtpCodes.NewCode(code, salt, hash(code, salt, purpose, phone))
    }

    override fun hash(code: OtpCode, salt: Salt, purpose: OtpPurpose, phone: IranianMobileNumber): CodeHash {
        val input = ByteArrayOutputStream().apply {
            field(salt.toByteArray())
            field(purpose.name.toByteArray(Charsets.US_ASCII))
            field(phone.value.toByteArray(Charsets.US_ASCII))
            field(code.value.toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        // A Mac holds state while it works, so each call gets its own: one shared instance would mix up two requests.
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        return CodeHash(mac.doFinal(input))
    }

    private fun ByteArrayOutputStream.field(bytes: ByteArray) {
        write(bytes)
        write(0)
    }

    companion object {
        const val MIN_PEPPER_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"
    }
}
