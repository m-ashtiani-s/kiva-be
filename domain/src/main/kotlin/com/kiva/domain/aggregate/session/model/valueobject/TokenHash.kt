package com.kiva.domain.aggregate.session.model.valueobject

import java.security.MessageDigest

/**
 * The stored form of a random token: its SHA-256 (32 bytes). Used for refresh tokens (made by the session token port)
 * and for guest cart tokens (CartToken.hash). Each token is 256 random bits, so a plain hash is enough: there is
 * nothing to try. Compared in constant time ([MessageDigest.isEqual]).
 *
 * Never user input: computed from a token, or read back from storage. So the constructor is public and a wrong length
 * is a bug. It keeps its own copy of the bytes.
 */
class TokenHash(bytes: ByteArray) {

    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == LENGTH) { "A TokenHash is $LENGTH bytes, not ${this.bytes.size}" }
    }

    fun matches(other: TokenHash): Boolean = MessageDigest.isEqual(bytes, other.bytes)

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is TokenHash && matches(other)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TokenHash($LENGTH bytes)"

    companion object {
        const val LENGTH = 32
    }
}
