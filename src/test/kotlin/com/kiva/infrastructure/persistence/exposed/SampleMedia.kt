package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.dto.GalleryItemDto
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.tbl.GalleryItemPayload
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/**
 * The uploaded files behind ProductEventSamples' gallery (its photo and its video), stored once in kiva_test's
 * media_tbl: the product projector copies a file's sizes from there, so an event with a gallery needs its files.
 * They are never removed (events of other tests point to them), and storing them again is a no-op.
 */
object SampleMedia {

    private val uploadedOn = Instant.parse("2026-10-01T00:00:00Z")

    private val photoDir = "2026/10/${ProductEventSamples.frontPhoto.mediaId.value}"
    private val videoDir = "2026/10/${ProductEventSamples.video.mediaId.value}"

    val photo: MediaAsset = MediaAsset.upload(
        ProductEventSamples.frontPhoto.mediaId, MediaPurpose.PRODUCT, MediaType.IMAGE, 1600, 1200, null, 120_000, "LKO2?U%2Tw=w",
        listOf(MediaVariant.of(320, "$photoDir/320.webp"), MediaVariant.of(1600, "$photoDir/1600.webp")), null, null,
        photoDir, UserId.SYSTEM, uploadedOn,
    )

    val video: MediaAsset = MediaAsset.upload(
        ProductEventSamples.video.mediaId, MediaPurpose.PRODUCT, MediaType.VIDEO, 1080, 1920, 14, 2_400_000, null,
        listOf(MediaVariant.of(480, "$videoDir/poster-480.webp"), MediaVariant.of(1080, "$videoDir/poster-1080.webp")),
        "$videoDir/poster-1080.webp", null, videoDir, UserId.SYSTEM, uploadedOn,
    )

    fun ensureStored() {
        transaction(TestDatabase.database) {
            val media = MediaRepositoryImpl()
            listOf(photo, video).filter { media.find(it.id) == null }.forEach(media::insert)
        }
    }

    /** What the read model gives back for [item] of the samples' gallery. */
    fun dtoOf(item: ProductMedia): GalleryItemDto =
        GalleryItemPayload(item, listOf(photo, video).single { it.id == item.mediaId }).toDto()
}
