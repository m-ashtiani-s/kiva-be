package com.kiva.domain.aggregate.media.repository

import com.kiva.domain.aggregate.media.model.valueobject.MediaId

/**
 * Hands out new media ids: `med_` and 22 random base62 characters. Implemented with a secure random source in
 * infrastructure (RandomMediaIds); the database's unique key is the wall behind a (practically impossible) repeat.
 */
interface MediaIdGenerator {
    fun next(): MediaId
}
