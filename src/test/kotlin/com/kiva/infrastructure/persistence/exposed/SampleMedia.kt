package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.dto.GalleryItemDto
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.tbl.GalleryItemPayload
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

/**
 * The uploaded files behind ProductEventSamples' gallery (its photo and its video), stored once in kiva_test's
 * media_tbl: the product projector copies a file's sizes from there, so an event with a gallery needs its files.
 * They are never removed (events of other tests point to them; stored as attached, so the clean-up job leaves them
 * too), and storing them again is a no-op.
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
            val missing = listOf(photo, video).filter { media.find(it.id) == null }
            missing.forEach(media::insert)
            // Attached, as a product's files are: the clean-up job never removes them.
            media.markAttached(listOf(photo.id, video.id), uploadedOn)
        }
    }

    /**
     * Photo number [n] of a test gallery (`med_…n`, its sizes 160, 480 and 1600), stored once like the samples, as a
     * [view] item of a gallery.
     */
    fun photo(n: Int, view: MediaView = MediaView.FRONT): ProductMedia {
        val id = MediaId.constructFromPersisted("med_" + "P${n}".padStart(22, '0'))
        val dir = "2026/10/${id.value}"
        val asset = MediaAsset.upload(
            id, MediaPurpose.PRODUCT, MediaType.IMAGE, 1600, 1200, null, 90_000, null,
            listOf(MediaVariant.of(160, "$dir/160.webp"), MediaVariant.of(480, "$dir/480.webp"), MediaVariant.of(1600, "$dir/1600.webp")), null, null,
            dir, UserId.SYSTEM, uploadedOn,
        )
        transaction(TestDatabase.database) {
            val media = MediaRepositoryImpl()
            if (media.find(id) == null) media.insert(asset)
            media.markAttached(listOf(id), uploadedOn)
        }
        return ProductMedia.constructFromPersisted(id, MediaType.IMAGE, view, null)
    }

    /** What the read model gives back for [item] of the samples' gallery. */
    fun dtoOf(item: ProductMedia): GalleryItemDto =
        GalleryItemPayload(item, listOf(photo, video).single { it.id == item.mediaId }).toDto()
}
