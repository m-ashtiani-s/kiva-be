package com.kiva.domain.aggregate.otp.model.valueobject

/**
 * 16 random bytes stored with one code and mixed into its hash, so two codes never have the same hash even when the
 * code, phone and purpose are the same.
 *
 * Never user input: made by the code port's secure random, or read back from storage. So the constructor is public
 * and a wrong length is a bug. It keeps its own copy of the bytes, so nobody can change it from outside.
 */
class Salt(bytes: ByteArray) {

    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == LENGTH) { "A Salt is $LENGTH bytes, not ${this.bytes.size}" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is Salt && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "Salt($LENGTH bytes)"

    companion object {
        const val LENGTH = 16
    }
}
