package com.kiva.domain.aggregate.reservation.model.valueobject

import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import java.security.MessageDigest

/**
 * What an address said when a reservation group started (roadmap section 13): SHA-256 of every part a courier reads.
 * An order joins the group only at the very same address: the same saved address, not edited since, so the bags of one
 * group never go to two places. Only the hash is kept: the group needs to compare, never to show.
 */
class AddressFingerprint private constructor(private val bytes: ByteArray) {

    init {
        require(bytes.size == LENGTH) { "An address fingerprint is $LENGTH bytes, not ${bytes.size}" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is AddressFingerprint && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "AddressFingerprint(****)"

    companion object {
        const val LENGTH = 32

        /** Every part of [address] a courier reads, each on its own line, so no two addresses run into one. */
        fun of(address: AddressSnapshot): AddressFingerprint {
            val text = listOf(
                address.provinceName, address.cityName, address.line.value, address.plaque?.value.orEmpty(), address.unit?.value.orEmpty(),
                address.postalCode.value, address.recipientName.value, address.recipientPhone.value,
            ).joinToString("\n")
            return AddressFingerprint(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
        }

        fun constructFromPersisted(bytes: ByteArray): AddressFingerprint = AddressFingerprint(bytes.copyOf())
    }
}
