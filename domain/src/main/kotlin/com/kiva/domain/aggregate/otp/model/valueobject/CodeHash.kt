package com.kiva.domain.aggregate.otp.model.valueobject

import java.security.MessageDigest

/**
 * The stored form of a code: an HMAC-SHA256 (32 bytes), made by the code port. The domain only compares two of them;
 * it never makes one.
 *
 * Compared in constant time ([MessageDigest.isEqual]): a comparison that stops at the first different byte answers a
 * little sooner the fewer bytes match, and that timing can be measured.
 *
 * Never user input: made by the code port, or read back from storage. So the constructor is public and a wrong length
 * is a bug. It keeps its own copy of the bytes.
 */
class CodeHash(bytes: ByteArray) {

    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == LENGTH) { "A CodeHash is $LENGTH bytes, not ${this.bytes.size}" }
    }

    /** The same hash, compared in constant time. */
    fun matches(other: CodeHash): Boolean = MessageDigest.isEqual(bytes, other.bytes)

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is CodeHash && matches(other)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "CodeHash($LENGTH bytes)"

    companion object {
        const val LENGTH = 32
    }
}
