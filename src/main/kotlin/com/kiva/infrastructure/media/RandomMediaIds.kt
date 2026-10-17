package com.kiva.infrastructure.media

import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaIdGenerator
import java.security.SecureRandom

/** Media ids from [SecureRandom]: `med_` and 22 letters and digits (about 130 bits), nothing anyone can count or guess. */
class RandomMediaIds(private val random: SecureRandom = SecureRandom()) : MediaIdGenerator {

    override fun next(): MediaId =
        MediaId.constructFromPersisted(MediaId.PREFIX + buildString(MediaId.RANDOM_LENGTH) { repeat(MediaId.RANDOM_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } })

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
