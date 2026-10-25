package com.kiva.infrastructure.blog

import com.kiva.application.port.VoterHashes
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A guest's address hashed with HMAC-SHA256 under [pepper] (`kiva.blog.voterPepper`), a key kept outside the database.
 * Keyed, because there are few enough IPv4 addresses to hash them all: a plain SHA-256 would be reversed in an afternoon.
 * Changing the key makes every guest a new voter, which only lets a guest vote once more.
 */
class HmacVoterHashes(pepper: ByteArray) : VoterHashes {

    init {
        require(pepper.size >= MIN_PEPPER_BYTES) { "The blog voter pepper must be at least $MIN_PEPPER_BYTES bytes, was ${pepper.size}" }
    }

    private val key = SecretKeySpec(pepper.copyOf(), ALGORITHM)

    override fun hashOf(ip: String): String {
        // A Mac holds state while it works, so each call gets its own.
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        return mac.doFinal(ip.trim().lowercase().toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_PEPPER_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"
    }
}
