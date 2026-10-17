package com.kiva.domain.aggregate.media

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import java.time.Instant

/** Media made the way an upload makes them, for the domain's tests. */
object MediaFixtures {

    val NOW: Instant = Instant.parse("2036-03-06T09:00:00Z")

    fun id(n: Int = 1): MediaId = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, 'A'))

    fun photo(id: MediaId = id(), purpose: MediaPurpose = MediaPurpose.PRODUCT, widths: List<Int> = listOf(160, 480, 800, 1200, 1600)): MediaAsset {
        val dir = "2036/03/${id.value}"
        return MediaAsset.upload(
            id = id, purpose = purpose, type = MediaType.IMAGE, width = widths.last(), height = widths.last() * 3 / 4, durationSec = null,
            bytes = 120_000, blurhash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH", variants = widths.map { MediaVariant.of(it, "$dir/w$it.webp") },
            posterPath = null, alt = null, storageDir = dir, uploadedBy = UserId.constructFromPersisted(7), now = NOW,
        )
    }

    fun video(id: MediaId = id(2), purpose: MediaPurpose = MediaPurpose.PRODUCT): MediaAsset {
        val dir = "2036/03/${id.value}"
        return MediaAsset.upload(
            id = id, purpose = purpose, type = MediaType.VIDEO, width = 1280, height = 720, durationSec = 24, bytes = 4_000_000, blurhash = null,
            variants = listOf(MediaVariant.of(480, "$dir/poster-w480.webp"), MediaVariant.of(800, "$dir/poster-w800.webp")),
            posterPath = "$dir/poster-w800.webp", alt = null, storageDir = dir, uploadedBy = UserId.constructFromPersisted(7), now = NOW,
        )
    }
}
